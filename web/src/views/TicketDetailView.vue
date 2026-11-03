<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Sparkles } from 'lucide-vue-next'
import StatusTag from '../components/StatusTag.vue'
import PriorityDot from '../components/PriorityDot.vue'
import KnowledgeReferences from '../components/KnowledgeReferences.vue'
import { ticketApi } from '../api/ticket'
import { aiApi } from '../api/ai'
import { kbApi } from '../api/kb'
import { useUserStore } from '../stores/user'
import { ACTION_LABEL, TICKET_CATEGORY, TICKET_STATUS } from '../constants/ticket'
import { deadlineLabel } from '../utils/ticket'

const route = useRoute()
const router = useRouter()
const store = useUserStore()
const id = Number(route.params.id)
const loading = ref(false)
const ticket = ref(null)
const operations = ref([])
const replies = ref([])
const attachments = ref([])
const failed = ref(false)
const replyForm = reactive({ content: '', visibility: 'PUBLIC' })
const replySaving = ref(false)
const fileRef = ref(null)
const uploading = ref(false)
const uploadVisibility = ref('PUBLIC')
const actionVisible = ref(false)
const actionType = ref('RESOLVE')
const comment = ref('')
const submitting = ref(false)
const assignVisible = ref(false)
const transfer = ref(false)
const assignTarget = ref(null)
const transferReason = ref('')
const suggestions = ref([])
const supports = ref([])
const assist = ref(null)
const assistLoading = ref(false)
const assistError = ref('')
const knowledgeVisible = ref(false)
const environment = ref('')
const knowledgeSaving = ref(false)
const isOwner = computed(() => ticket.value?.creatorId === store.userId)
const canHandle = computed(() => store.isAdmin || (store.isStaff && ticket.value?.assigneeId === store.userId))
const canClaim = computed(() => store.isSupport && ticket.value?.status === 'OPEN')
const canAssign = computed(() => store.isAdmin && ticket.value?.status === 'OPEN')
const canTransfer = computed(() => canHandle.value && ['IN_PROGRESS', 'WAITING_CUSTOMER'].includes(ticket.value?.status))
const canResolve = computed(() => canHandle.value && ticket.value?.status === 'IN_PROGRESS')
const canConfirm = computed(() => (store.isAdmin || isOwner.value) && ticket.value?.status === 'RESOLVED')
const canProvide = computed(() => (store.isAdmin || isOwner.value) && ticket.value?.status === 'WAITING_CUSTOMER')
const actionTitle = computed(() => ({ RESOLVE: '标记为已解决', CLOSE: '确认关闭工单', REQUEST_INFO: '请求补充信息', PROVIDE_INFO: '确认已补充信息', REOPEN: '问题未解决，重新处理' })[actionType.value])

async function load() {
  loading.value = true
  failed.value = false
  const results = await Promise.allSettled([ticketApi.detail(id), ticketApi.operations(id), ticketApi.replies(id), ticketApi.attachments(id)])
  const destinations = [ticket, operations, replies, attachments]
  results.forEach((result, index) => {
    if (result.status === 'fulfilled') destinations[index].value = result.value
    else failed.value = true
  })
  loading.value = false
}
onMounted(load)
async function claim() {
  try { await ElMessageBox.confirm('接单后由你负责处理该工单。', '确认接单', { confirmButtonText: '确认接单', cancelButtonText: '取消' }) }
  catch { return }
  try { await ticketApi.claim(id); ElMessage.success('接单成功'); await load() }
  catch (error) { console.error('接单失败', error); await load() }
}
function openAction(type) { actionType.value = type; comment.value = ''; actionVisible.value = true }
async function submitAction() {
  if (submitting.value) return
  if (['REQUEST_INFO', 'PROVIDE_INFO', 'REOPEN'].includes(actionType.value) && !comment.value.trim()) {
    ElMessage.warning('请说明需要补充的信息或未解决的现象')
    return
  }
  submitting.value = true
  try {
    await ticketApi.changeStatus(id, { action: actionType.value, comment: comment.value.trim() || undefined })
    ElMessage.success(actionTitle.value)
    actionVisible.value = false
    await load()
  } catch (error) { console.error('状态变更失败', error) }
  finally { submitting.value = false }
}
async function sendReply() {
  if (!replyForm.content.trim() || replySaving.value) return
  replySaving.value = true
  try {
    await ticketApi.reply(id, { content: replyForm.content.trim(), visibility: store.isStaff ? replyForm.visibility : 'PUBLIC' })
    replyForm.content = ''
    ElMessage.success('回复已发送')
    await load()
  } catch (error) { console.error('发送回复失败', error) }
  finally { replySaving.value = false }
}
async function uploadFile(event) {
  const file = event.target.files?.[0]
  if (!file || uploading.value) return
  if (file.size > 5 * 1024 * 1024) { ElMessage.warning('附件不能超过 5 MB'); event.target.value = ''; return }
  uploading.value = true
  try {
    await ticketApi.upload(id, file, store.isStaff ? uploadVisibility.value : 'PUBLIC')
    ElMessage.success('附件已上传')
    attachments.value = await ticketApi.attachments(id)
  } catch (error) { console.error('附件上传失败', error) }
  finally { uploading.value = false; event.target.value = '' }
}
async function openAssign(isTransfer = false) {
  transfer.value = isTransfer
  assignTarget.value = null
  transferReason.value = ''
  assignVisible.value = true
  const results = await Promise.allSettled([ticketApi.supportOptions(), ticketApi.assignmentSuggestions(id)])
  if (results[0].status === 'fulfilled') supports.value = results[0].value
  if (results[1].status === 'fulfilled') suggestions.value = results[1].value
}
async function submitAssign() {
  if (!assignTarget.value || submitting.value) return
  if (transfer.value && !transferReason.value.trim()) { ElMessage.warning('请填写转派原因'); return }
  submitting.value = true
  try {
    if (transfer.value) await ticketApi.transfer(id, { assigneeId: assignTarget.value, reason: transferReason.value.trim() })
    else await ticketApi.assign(id, assignTarget.value)
    ElMessage.success(transfer.value ? '工单已转派' : '工单已指派')
    assignVisible.value = false
    await load()
  } catch (error) { console.error('分派失败', error) }
  finally { submitting.value = false }
}
async function getAssist() {
  if (assistLoading.value) return
  assistLoading.value = true
  assistError.value = ''
  try { assist.value = await aiApi.assist(id) }
  catch (error) { assistError.value = error?.response?.data?.message || '暂时无法整理建议，请稍后重试。' }
  finally { assistLoading.value = false }
}
async function applyReply() {
  replyForm.content = assist.value.replyDraft
  replyForm.visibility = 'PUBLIC'
  if (assist.value.answerId) {
    try { await aiApi.feedback(assist.value.answerId, { adopted: true }) }
    catch (error) { console.debug('建议采纳反馈暂未保存', error) }
  }
  ElMessage.success('已填入回复框，请核对后发送')
}
async function createKnowledge() {
  if (knowledgeSaving.value) return
  knowledgeSaving.value = true
  try {
    const draft = await kbApi.draftFromTicket({ ticketId: id, environment: environment.value.trim() })
    ElMessage.success('已生成知识草稿，请审核后发布')
    router.push({ name: 'kb', query: { draftId: draft.id } })
  } catch (error) { console.error('生成知识草稿失败', error) }
  finally { knowledgeSaving.value = false }
}
function openAi() { window.dispatchEvent(new CustomEvent('isd:open-ai')) }
</script>

<template>
  <div v-loading="loading">
    <div class="mb-5 flex items-center justify-between gap-2"><el-button text :icon="ArrowLeft" @click="router.push({ name: 'tickets' })">返回列表</el-button><el-button :icon="Sparkles" @click="openAi">问问当前工单助手</el-button></div>
    <p v-if="failed" class="text-[13px] text-red-700" role="alert">部分工单内容未加载成功。<button class="isd-text-action" @click="load">重新加载</button></p>
    <div v-if="ticket" class="grid items-start gap-5 xl:grid-cols-3">
      <div class="space-y-5 xl:col-span-2">
        <section class="isd-card p-5 sm:p-6">
          <div class="flex items-start justify-between gap-4"><div><p class="m-0 text-xs text-ink-400">{{ ticket.ticketNo }}</p><h1 class="mt-2 mb-0 text-[22px] font-semibold leading-8 text-ink-900">{{ ticket.title }}</h1></div><StatusTag :status="ticket.status" data-testid="ticket-status" /></div>
          <dl class="mt-5 grid grid-cols-2 gap-x-4 gap-y-4 text-[13px] sm:grid-cols-3">
            <div><dt class="text-xs text-ink-600">分类</dt><dd class="m-0 mt-1">{{ TICKET_CATEGORY[ticket.category] || ticket.category }}</dd></div>
            <div><dt class="text-xs text-ink-600">优先级</dt><dd class="m-0 mt-1"><PriorityDot :priority="ticket.priority" /></dd></div>
            <div><dt class="text-xs text-ink-600">处理人</dt><dd class="m-0 mt-1">{{ ticket.assigneeName || '等待接单' }}</dd></div>
            <div><dt class="text-xs text-ink-600">创建人</dt><dd class="m-0 mt-1">{{ ticket.creatorName || `用户 ${ticket.creatorId}` }}</dd></div>
            <div><dt class="text-xs text-ink-600">创建时间</dt><dd class="m-0 mt-1">{{ ticket.createdAt }}</dd></div>
            <div><dt class="text-xs text-ink-600">更新时间</dt><dd class="m-0 mt-1">{{ ticket.updatedAt }}</dd></div>
          </dl>
          <p class="mb-0 border-t border-line pt-4 whitespace-pre-wrap text-[13px] leading-7 text-ink-600">{{ ticket.description || '提交时未填写描述，可在下方补充。' }}</p>
        </section>
        <section class="isd-card p-5 sm:p-6">
          <h2 class="m-0 text-[15px] font-semibold text-ink-900">沟通与补充信息</h2>
          <p v-if="!replies.length" class="text-[13px] text-ink-600">暂无回复。补充现象、错误提示或排查进展，帮助双方继续处理。</p>
          <article v-for="reply in replies" :key="reply.id" class="mt-4 border-b border-line pb-4 last:border-0" :class="reply.visibility === 'INTERNAL' ? 'rounded bg-amber-50 px-3 pt-3' : ''">
            <div class="flex flex-wrap items-center gap-2 text-xs"><span class="font-medium text-ink-900">{{ reply.authorName || `用户 ${reply.authorId}` }}</span><span class="text-ink-400">{{ reply.createdAt }}</span><el-tag v-if="reply.visibility === 'INTERNAL'" size="small" type="warning">内部备注</el-tag></div>
            <p class="mb-0 whitespace-pre-wrap text-[13px] leading-7 text-ink-600">{{ reply.content }}</p>
          </article>
          <div v-if="ticket.status !== 'CLOSED'" class="mt-4 border-t border-line pt-4">
            <div v-if="store.isStaff" class="mb-3 flex gap-2"><el-radio-group v-model="replyForm.visibility" size="small"><el-radio-button value="PUBLIC">回复员工</el-radio-button><el-radio-button value="INTERNAL">内部备注</el-radio-button></el-radio-group></div>
            <p v-if="replyForm.visibility === 'INTERNAL' && store.isStaff" class="text-xs text-amber-800">仅技术支持和管理员可见，员工不会收到此内容。</p>
            <el-input v-model="replyForm.content" type="textarea" :rows="4" maxlength="4000" show-word-limit aria-label="工单回复" :placeholder="replyForm.visibility === 'INTERNAL' ? '记录内部排查信息' : '填写补充信息或处理进展'" />
            <div class="mt-3 flex justify-end"><el-button type="primary" :loading="replySaving" :disabled="!replyForm.content.trim()" data-testid="send-reply" @click="sendReply">{{ replyForm.visibility === 'INTERNAL' && store.isStaff ? '保存内部备注' : '发送回复' }}</el-button></div>
          </div>
        </section>
        <section class="isd-card p-5 sm:p-6">
          <div class="flex flex-wrap items-center justify-between gap-3"><h2 class="m-0 text-[15px] font-semibold text-ink-900">截图与附件</h2><div v-if="ticket.status !== 'CLOSED'" class="flex items-center gap-2"><el-select v-if="store.isStaff" v-model="uploadVisibility" class="!w-[130px]" size="small"><el-option label="员工可见" value="PUBLIC" /><el-option label="仅内部可见" value="INTERNAL" /></el-select><el-button :loading="uploading" size="small" @click="fileRef?.click()">上传附件</el-button><input ref="fileRef" type="file" class="hidden" aria-label="上传附件" @change="uploadFile" /></div></div>
          <p class="text-xs text-ink-600">每个文件不超过 5 MB，支持截图、PDF 和文本；请勿上传密码或密钥。</p>
          <ul class="mb-0 space-y-3 pl-0"><li v-for="file in attachments" :key="file.id" class="flex list-none flex-wrap items-center justify-between gap-2 text-[13px]"><a :href="ticketApi.attachmentUrl(id, file.id)" class="text-brand-700 underline underline-offset-2">{{ file.fileName }}</a><span class="text-xs text-ink-400">{{ Math.ceil(file.size / 1024) }} KB <span v-if="file.visibility === 'INTERNAL'">· 仅内部可见</span></span></li></ul>
        </section>
        <section v-if="store.isStaff" class="isd-card isd-assistance p-5 sm:p-6">
          <div class="flex flex-wrap items-center justify-between gap-3"><h2 class="m-0 text-[15px] font-semibold text-ink-900">处理建议</h2><el-button :loading="assistLoading" :icon="Sparkles" @click="getAssist">{{ assist ? '重新整理建议' : '生成处理建议' }}</el-button></div>
          <p class="text-xs leading-6 text-ink-600">结合当前工单、处理记录和相似案例生成，采用前请核对。</p>
          <p v-if="assistError" role="alert" class="text-[13px] text-red-700">{{ assistError }}</p>
          <div v-if="assist">
            <p class="whitespace-pre-wrap text-[13px] leading-7 text-ink-900">{{ assist.summary }}</p>
            <ol v-if="assist.steps?.length" class="pl-5 text-[13px] leading-7 text-ink-600"><li v-for="step in assist.steps" :key="step">{{ step }}</li></ol>
            <div v-if="assist.replyDraft" class="mt-4 rounded border border-line bg-canvas p-4"><h3 class="m-0 text-[13px] font-medium">回复草稿</h3><p class="whitespace-pre-wrap text-[13px] leading-6 text-ink-600">{{ assist.replyDraft }}</p><el-button size="small" :disabled="ticket.status === 'CLOSED'" @click="applyReply">填入回复框</el-button></div>
            <div v-if="assist.similarTickets?.length" class="mt-4 space-y-2"><h3 class="text-[13px] font-medium">相似已解决案例</h3><router-link v-for="item in assist.similarTickets" :key="item.id" :to="{ name: 'ticket-detail', params: { id: item.id } }" class="block text-[13px] text-brand-700">{{ item.ticketNo }} {{ item.title }}<span class="mt-1 block text-xs leading-5 text-ink-600">{{ item.solution }}</span></router-link></div>
            <KnowledgeReferences :references="assist.references" />
          </div>
        </section>
      </div>
      <aside class="space-y-5">
        <section class="isd-card p-5">
          <h2 class="m-0 text-[15px] font-semibold text-ink-900">下一步</h2>
          <div class="mt-4 flex flex-col gap-2">
            <el-button v-if="canClaim" type="primary" data-testid="action-claim" @click="claim">接单处理</el-button>
            <el-button v-if="canAssign" type="primary" @click="openAssign(false)">指派给技术支持</el-button>
            <el-button v-if="canResolve" type="success" data-testid="action-resolve" @click="openAction('RESOLVE')">标记为已解决</el-button>
            <el-button v-if="canResolve" @click="openAction('REQUEST_INFO')">请求员工补充信息</el-button>
            <el-button v-if="canProvide" type="primary" @click="openAction('PROVIDE_INFO')">我已补充，请继续处理</el-button>
            <el-button v-if="canConfirm" type="primary" data-testid="action-close" @click="openAction('CLOSE')">确认关闭工单</el-button>
            <el-button v-if="canConfirm" @click="openAction('REOPEN')">问题未解决</el-button>
            <el-button v-if="canTransfer" @click="openAssign(true)">转派给其他技术支持</el-button>
            <p v-if="!canClaim && !canAssign && !canResolve && !canProvide && !canConfirm && !canTransfer" class="m-0 text-[13px] leading-6 text-ink-600">{{ ticket.status === 'CLOSED' ? '工单已关闭。再次遇到问题可以提交新工单。' : '正在等待相关人员处理，你可以在工单内补充信息。' }}</p>
          </div>
          <el-button v-if="store.isStaff && ['RESOLVED', 'CLOSED'].includes(ticket.status)" class="mt-4 !ml-0 w-full" @click="knowledgeVisible = true">整理为知识草稿</el-button>
        </section>
        <section class="isd-card p-5">
          <h2 class="m-0 text-[15px] font-semibold text-ink-900">处理时限</h2>
          <p class="mt-3 text-[13px] leading-6 text-ink-600">{{ deadlineLabel(ticket.resolutionDueAt, ticket.status) }}</p>
          <dl class="space-y-3 text-xs text-ink-600"><div><dt>首次响应</dt><dd class="m-0 mt-1">{{ ticket.firstResponseAt || (ticket.responseDueAt ? `应在 ${ticket.responseDueAt} 前响应` : '等待响应') }}</dd></div><div v-if="ticket.resolutionDueAt"><dt>预计解决时限</dt><dd class="m-0 mt-1">{{ ticket.resolutionDueAt }}</dd></div></dl>
          <p v-if="ticket.reopenCount" class="mb-0 text-xs text-ink-400">已重新处理 {{ ticket.reopenCount }} 次</p>
        </section>
        <section class="isd-card p-5"><h2 class="m-0 text-[15px] font-semibold text-ink-900">处理时间线</h2><el-timeline class="mt-4 pl-1"><el-timeline-item v-for="log in operations" :key="log.id" :timestamp="log.createdAt" placement="top"><p class="m-0 text-[13px] font-medium">{{ ACTION_LABEL[log.action] || log.action }}</p><p class="my-1 text-xs text-ink-400">{{ log.operatorName || `用户 ${log.operatorId}` }}<span v-if="log.toStatus"> · {{ TICKET_STATUS[log.toStatus]?.label || log.toStatus }}</span></p><p v-if="log.comment" class="mb-0 whitespace-pre-wrap text-xs leading-5 text-ink-600">{{ log.comment }}</p></el-timeline-item></el-timeline></section>
      </aside>
    </div>
    <el-dialog v-model="actionVisible" :title="actionTitle" width="min(460px, 94vw)">
      <p class="text-[13px] leading-6 text-ink-600">{{ actionType === 'REQUEST_INFO' ? '写清需要补充的内容。等待期间会暂停解决时限计时。' : actionType === 'REOPEN' ? '描述仍然存在的问题，工单将返回原处理人继续处理。' : actionType === 'CLOSE' ? '请确认实际问题已解决。' : '补充处理说明，便于双方了解进展。' }}</p>
      <el-input v-model="comment" type="textarea" :rows="4" maxlength="500" show-word-limit aria-label="处理说明" />
      <template #footer><el-button @click="actionVisible = false">取消</el-button><el-button type="primary" data-testid="action-confirm" :loading="submitting" @click="submitAction">确认</el-button></template>
    </el-dialog>
    <el-dialog v-model="assignVisible" :title="transfer ? '转派工单' : '指派工单'" width="min(560px, 94vw)">
      <div v-if="suggestions.length" class="mb-4 space-y-2"><p class="text-[13px] font-medium">按技能和当前负荷推荐</p><button v-for="item in suggestions" :key="item.userId" class="block w-full rounded border px-3 py-3 text-left" :class="assignTarget === item.userId ? 'border-brand-600 bg-brand-50' : 'border-line'" @click="assignTarget = item.userId"><span class="text-[13px] font-medium">{{ item.name }} · 在办 {{ item.activeTickets }} 单</span><span class="mt-1 block text-xs leading-5 text-ink-600">{{ item.reason }}</span></button></div>
      <el-select v-model="assignTarget" placeholder="选择技术支持" class="w-full"><el-option v-for="person in supports" :key="person.userId" :label="`${person.name}（在办 ${person.activeTickets} 单）`" :value="person.userId" /></el-select>
      <el-input v-if="transfer" v-model="transferReason" class="mt-4" type="textarea" :rows="3" maxlength="500" placeholder="填写转派原因" />
      <p class="text-xs text-ink-400">推荐不会自动分派，确认后才会更改处理人。</p>
      <template #footer><el-button @click="assignVisible = false">取消</el-button><el-button type="primary" :disabled="!assignTarget" :loading="submitting" @click="submitAssign">确认{{ transfer ? '转派' : '指派' }}</el-button></template>
    </el-dialog>
    <el-dialog v-model="knowledgeVisible" title="从解决记录生成知识草稿" width="min(480px, 94vw)"><p class="text-[13px] leading-6 text-ink-600">草稿生成后进入审核页，你可以修改内容、选择分类并确认发布。</p><el-input v-model="environment" maxlength="255" placeholder="适用环境，例如 Windows 11 / 公司 VPN" /><template #footer><el-button @click="knowledgeVisible = false">取消</el-button><el-button type="primary" :loading="knowledgeSaving" @click="createKnowledge">生成草稿</el-button></template></el-dialog>
  </div>
</template>
