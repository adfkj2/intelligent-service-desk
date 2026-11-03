<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, RefreshCw } from 'lucide-vue-next'
import PageHeader from '../components/PageHeader.vue'
import { adminAiApi } from '../api/adminAi'
import { adminApi } from '../api/admin'

const providers = [
  { value: 'DEEPSEEK', label: 'DeepSeek', baseUrl: 'https://api.deepseek.com', model: 'deepseek-chat' },
  { value: 'QWEN', label: '通义千问', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus' },
  { value: 'OPENAI', label: 'OpenAI', baseUrl: 'https://api.openai.com/v1', model: '' },
  { value: 'CUSTOM', label: '自定义兼容服务', baseUrl: '', model: '' }
]
const purposes = { CHAT: '问答', DRAFT: '智能建单', ASSIST: '处理助手', KNOWLEDGE_DRAFT: '知识草稿', CONNECTION_TEST: '连接测试' }
const outcomes = { PENDING: '进行中', SUCCESS: '成功', FALLBACK: '已降级', TIMEOUT: '响应超时', ERROR: '失败' }
const upstreamOutcomes = { PENDING: '进行中', SUCCESS: '成功', ERROR: '失败', INTERRUPTED: '已中断' }
const testLabels = { UNTESTED: '待测试', PASSED: '测试通过', FAILED: '测试失败' }
const tab = ref('config')
const loading = ref(false)
const failed = ref(false)
const configurations = ref([])
const runtime = ref(null)
const busy = ref('')
const editorVisible = ref(false)
const saving = ref(false)
const editingId = ref(null)
const editor = reactive(emptyEditor())
const versionsVisible = ref(false)
const versionRows = ref([])
const versionsName = ref('')
const versionsLoading = ref(false)
const balanceVisible = ref(false)
const balanceTarget = ref(null)
const balance = ref(null)
const balanceLoading = ref(false)
const usageLoading = ref(false)
const usageFailed = ref(false)
const usageLoaded = ref(false)
const summary = ref(null)
const daily = ref([])
const usageUsers = ref([])
const userOptions = ref([])
const calls = ref({ items: [], page: 1, size: 10, total: 0 })
const period = ref('30')
const filters = reactive({ from: minusDays(today(), 29), to: today(), provider: '', configurationId: '', model: '', userId: '', purpose: '', outcome: '' })
const appliedFilters = ref({})
let usageSequence = 0
let configSequence = 0
let balanceSequence = 0

const visibleConfigurations = computed(() => configurations.value.filter(item => !item.archived))
const currentName = computed(() => configurations.value.find(item => item.id === runtime.value?.configurationId)?.name || providerName(runtime.value?.provider))
const runtimeTitle = computed(() => {
  if (!runtime.value) return '正在读取当前服务'
  if (runtime.value.mode === 'DISABLED') return 'AI 已停用'
  if (runtime.value.mode === 'ENV') return runtime.value.keyConfigured ? '使用部署配置' : '尚未启用模型服务'
  return currentName.value
})
const dailyMaximum = computed(() => Math.max(1, ...daily.value.map(item => item.calls || 0)))
const knownCosts = computed(() => summary.value?.costs || [])
const unknownCosts = computed(() => knownCosts.value.reduce((total, item) => total + Number(item.unknownCostCalls || 0), 0))

function emptyEditor() {
  return { version: null, name: '', provider: 'DEEPSEEK', baseUrl: 'https://api.deepseek.com', model: 'deepseek-chat', apiKey: '', currency: '', inputPrice: '', cachedInputPrice: '', outputPrice: '' }
}
function providerName(provider) { return providers.find(item => item.value === provider)?.label || provider || '未选择供应商' }
function number(value) { return value == null ? '未知' : Number(value).toLocaleString('zh-CN') }
function tokenValue(row, field) {
  const unknown = !row.usageComplete && row.upstreamRequests > 0 && !Number(row.inputTokens) && !Number(row.cachedInputTokens) && !Number(row.outputTokens)
  return unknown ? '未知' : number(row[field])
}
function money(value) { return value == null ? '未知' : Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 6 }) }
function dateTime(value) { return value ? value.replace('T', ' ').slice(0, 19) : '尚无记录' }
function shortDate(value) { return value?.slice(5) || '' }
function today() {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date())
  return ['year', 'month', 'day'].map(type => parts.find(item => item.type === type).value).join('-')
}
function minusDays(value, days) { return new Date(Date.parse(`${value}T00:00:00Z`) - days * 86400000).toISOString().slice(0, 10) }
function fillDaily(rows, from, to) {
  const byDate = new Map(rows.map(row => [row.date, row]))
  const result = []
  for (let date = from; date <= to; date = minusDays(date, -1)) {
    result.push(byDate.get(date) || { date, calls: 0, upstreamRequests: 0, inputTokens: 0, cachedInputTokens: 0, outputTokens: 0, failedCalls: 0 })
  }
  return result
}
function isCurrent(row) { return runtime.value?.mode === 'MANAGED' && runtime.value.configurationId === row.id && runtime.value.configurationVersion === row.version }
function currentUsesConfiguration(row) { return runtime.value?.mode === 'MANAGED' && runtime.value.configurationId === row.id }
function safePortal(value) {
  try { const url = new URL(value); return url.protocol === 'https:' && !url.username && !url.password ? url.href : null } catch { return null }
}
async function confirm(message, title, confirmButtonText) {
  try { await ElMessageBox.confirm(message, title, { confirmButtonText, cancelButtonText: '取消', type: 'warning' }); return true } catch { return false }
}
async function loadConfigurations() {
  const sequence = ++configSequence
  loading.value = true
  failed.value = false
  try {
    const [rows, state] = await Promise.all([adminAiApi.configurations(), adminAiApi.runtime()])
    if (sequence !== configSequence) return
    configurations.value = rows
    runtime.value = state
  } catch { if (sequence === configSequence) failed.value = true } finally { if (sequence === configSequence) loading.value = false }
}
function refresh() {
  loadConfigurations()
  if (tab.value === 'usage') loadUsage()
}
function openEditor(row = null) {
  editingId.value = row?.id || null
  Object.assign(editor, emptyEditor(), row ? { version: row.version, name: row.name, provider: row.provider, baseUrl: row.baseUrl, model: row.model, currency: row.currency || '', inputPrice: row.inputPrice ?? '', cachedInputPrice: row.cachedInputPrice ?? '', outputPrice: row.outputPrice ?? '' } : {})
  editor.apiKey = ''
  editorVisible.value = true
}
function changeProvider(value) {
  const preset = providers.find(item => item.value === value)
  editor.baseUrl = preset.baseUrl
  editor.model = preset.model
}
function clearSecret() { editor.apiKey = '' }
async function saveConfiguration() {
  if (!editor.name.trim() || !editor.baseUrl.trim() || !editor.model.trim() || (!editingId.value && !editor.apiKey.trim())) {
    ElMessage.warning('请填写配置名称、接口地址、模型和 API Key')
    return
  }
  const prices = ['inputPrice', 'cachedInputPrice', 'outputPrice']
  if (prices.some(key => editor[key] !== '' && (!Number.isFinite(Number(editor[key])) || Number(editor[key]) < 0))) {
    ElMessage.warning('单价应为大于或等于 0 的数字')
    return
  }
  if (prices.some(key => editor[key] !== '') && !editor.currency) {
    ElMessage.warning('填写单价时请选择计费币种')
    return
  }
  saving.value = true
  try {
    const payload = { ...editor, name: editor.name.trim(), baseUrl: editor.baseUrl.trim(), model: editor.model.trim(), apiKey: editor.apiKey.trim() || null, currency: editor.currency || null }
    prices.forEach(key => { payload[key] = editor[key] === '' ? null : String(editor[key]) })
    if (editingId.value) await adminAiApi.update(editingId.value, payload)
    else await adminAiApi.create(payload)
    editorVisible.value = false
    ElMessage.success('已保存待启用版本，请测试连接后设为当前服务')
    await loadConfigurations()
  } catch (error) {
    if (error?.response?.status === 409) { editorVisible.value = false; await loadConfigurations() }
  } finally { clearSecret(); saving.value = false }
}
async function testConfiguration(row) {
  if (!await confirm('将使用固定测试文本和无业务数据的工具调用验证连接，可能产生少量 Token 费用。不会切换当前服务。', '测试连接', '开始测试')) return
  busy.value = `test-${row.id}`
  try {
    const tested = await adminAiApi.test(row.id, row.version)
    if (tested.testStatus === 'PASSED') ElMessage.success('连接与工具调用测试通过，可设为当前服务')
    else ElMessage.warning(tested.testMessage || '连接测试失败，请检查接口、模型和 Key')
    await loadConfigurations()
  } catch (error) { if (error?.response?.status === 409) await loadConfigurations() } finally { busy.value = '' }
}
async function activate(row) {
  if (!await confirm(`确认将「${row.name}」版本 ${row.version} 设为当前服务？后续 AI 请求将使用 ${providerName(row.provider)} 的 ${row.model}，正在进行的请求保持原配置。`, '切换当前服务', '确认切换')) return
  busy.value = `activate-${row.id}`
  try {
    await adminAiApi.setRuntime({ version: runtime.value.version, mode: 'MANAGED', configurationId: row.id, configurationVersion: row.version })
    ElMessage.success('当前服务已切换，无需重启')
    versionsVisible.value = false
    await loadConfigurations()
  } catch (error) { if (error?.response?.status === 409) { versionsVisible.value = false; await loadConfigurations() } } finally { busy.value = '' }
}
async function disable() {
  if (!await confirm('停用后，后续请求只使用知识库降级建议，不再调用模型，也不会回退到部署中的 API Key。正在进行的调用仍可能产生费用。', '停用 AI', '确认停用')) return
  busy.value = 'disable'
  try {
    await adminAiApi.setRuntime({ version: runtime.value.version, mode: 'DISABLED', configurationId: null, configurationVersion: null })
    ElMessage.success('AI 已停用，工单与知识库仍可正常使用')
    await loadConfigurations()
  } catch (error) { if (error?.response?.status === 409) await loadConfigurations() } finally { busy.value = '' }
}
async function archive(row) {
  if (!await confirm(`确认归档「${row.name}」？此配置的 Key 将被移除，用量和操作历史仍会保留。`, '归档配置', '确认归档')) return
  busy.value = `archive-${row.id}`
  try { await adminAiApi.archive(row.id, row.version); ElMessage.success('配置已归档'); await loadConfigurations() } catch (error) { if (error?.response?.status === 409) await loadConfigurations() } finally { busy.value = '' }
}
async function showVersions(row) {
  versionsName.value = row.name
  versionRows.value = []
  versionsVisible.value = true
  versionsLoading.value = true
  try { versionRows.value = await adminAiApi.versions(row.id) } catch { versionsVisible.value = false } finally { versionsLoading.value = false }
}
async function showBalance(row) {
  const sequence = ++balanceSequence
  balanceTarget.value = row
  balance.value = null
  balanceVisible.value = true
  balanceLoading.value = true
  try { const result = await adminAiApi.balance(row.id); if (sequence === balanceSequence) balance.value = result } catch { if (sequence === balanceSequence) balanceVisible.value = false } finally { if (sequence === balanceSequence) balanceLoading.value = false }
}
async function refreshBalance() {
  if (!balanceTarget.value) return
  const id = balanceTarget.value.id
  const sequence = ++balanceSequence
  balanceLoading.value = true
  try { const result = await adminAiApi.refreshBalance(id); if (sequence === balanceSequence) balance.value = result } catch {
    if (sequence === balanceSequence && balance.value) balance.value = { ...balance.value, stale: true, message: '刷新失败，请稍后重试。' }
  } finally { if (sequence === balanceSequence) balanceLoading.value = false }
}
async function selectTab(value) {
  tab.value = value
  if (value === 'usage' && !usageLoaded.value) {
    adminApi.users().then(rows => { userOptions.value = rows }).catch(() => {})
    await loadUsage()
  }
}
function queryFilters() {
  const days = (Date.parse(`${filters.to}T00:00:00Z`) - Date.parse(`${filters.from}T00:00:00Z`)) / 86400000 + 1
  if (!Number.isFinite(days) || days < 1 || days > 365) { ElMessage.warning('请选择起止时间，单次查询范围最多 365 天'); return null }
  return Object.fromEntries(Object.entries(filters).filter(([_key, value]) => value !== '' && value != null).map(([key, value]) => [key, typeof value === 'string' ? value.trim() : value]))
}
async function loadUsage() {
  const params = queryFilters()
  if (!params) return
  const sequence = ++usageSequence
  usageLoading.value = true
  usageFailed.value = false
  try {
    const results = await Promise.all([adminAiApi.summary(params), adminAiApi.daily(params), adminAiApi.users(params), adminAiApi.calls({ ...params, page: 1, size: 10 })])
    if (sequence !== usageSequence) return
    ;[summary.value, daily.value, usageUsers.value, calls.value] = results
    daily.value = fillDaily(daily.value, params.from, params.to)
    appliedFilters.value = params
    usageLoaded.value = true
  } catch { if (sequence === usageSequence) usageFailed.value = true } finally { if (sequence === usageSequence) usageLoading.value = false }
}
async function changePage(page) {
  const sequence = ++usageSequence
  usageLoading.value = true
  try { const result = await adminAiApi.calls({ ...appliedFilters.value, page, size: 10 }); if (sequence === usageSequence) calls.value = result } catch { /* Keep the last successfully loaded page. */ } finally { if (sequence === usageSequence) usageLoading.value = false }
}
async function selectPeriod(value) {
  period.value = value
  if (value === 'custom') return
  filters.to = today()
  filters.from = minusDays(filters.to, Number(value) - 1)
  await loadUsage()
}
function resetFilters() {
  Object.assign(filters, { from: minusDays(today(), 29), to: today(), provider: '', configurationId: '', model: '', userId: '', purpose: '', outcome: '' })
  period.value = '30'
  loadUsage()
}
onMounted(loadConfigurations)
onBeforeUnmount(() => { clearSecret(); usageSequence++; configSequence++; balanceSequence++ })
</script>

<template>
  <div class="ai-admin">
    <PageHeader title="AI 配置与用量" description="明确选择模型服务，查看本系统的调用消耗与费用估算。">
      <template #actions>
        <el-button :loading="loading || usageLoading" aria-label="刷新 AI 管理数据" @click="refresh"><RefreshCw :size="14" class="mr-1.5" />刷新</el-button>
        <el-button type="primary" :disabled="runtime?.credentialsReady === false" @click="openEditor()"><Plus :size="15" class="mr-1.5" />新增配置</el-button>
      </template>
    </PageHeader>

    <div v-if="failed" class="mb-4 rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800" role="alert">配置读取失败，请点击刷新重试。</div>
    <section class="isd-card mb-5 flex flex-col justify-between gap-3 border-l-4 px-5 py-4 sm:flex-row sm:items-center" :class="runtime?.mode === 'DISABLED' ? 'border-l-gray-400' : 'border-l-brand-600'" aria-label="当前 AI 服务">
      <div class="min-w-0">
        <p class="m-0 text-xs text-ink-600">当前服务</p>
        <div class="mt-1 flex flex-wrap items-center gap-2">
          <h2 class="m-0 text-base font-semibold text-ink-900">{{ runtimeTitle }}</h2>
          <span v-if="runtime?.mode === 'MANAGED'" class="ai-label bg-brand-50 text-brand-700">版本 {{ runtime.configurationVersion }}</span>
          <span v-if="runtime?.model && runtime.mode !== 'DISABLED'" class="break-all text-sm text-ink-600">{{ runtime.model }}</span>
        </div>
        <p class="mb-0 mt-1.5 max-w-3xl text-xs leading-5 text-ink-600">{{ runtime?.message || '保存配置后先测试，再确认设为当前服务。' }}</p>
      </div>
      <el-button v-if="runtime && runtime.mode !== 'DISABLED'" :loading="busy === 'disable'" :disabled="Boolean(busy)" @click="disable">停用 AI</el-button>
    </section>

    <div class="mb-5 flex gap-5 border-b border-line" role="tablist" aria-label="AI 管理内容">
      <button id="ai-config-tab" type="button" role="tab" :aria-selected="tab === 'config'" aria-controls="ai-config-panel" class="ai-tab" :class="{ active: tab === 'config' }" @click="selectTab('config')">服务配置</button>
      <button id="ai-usage-tab" type="button" role="tab" :aria-selected="tab === 'usage'" aria-controls="ai-usage-panel" class="ai-tab" :class="{ active: tab === 'usage' }" @click="selectTab('usage')">用量与费用</button>
    </div>

    <section v-if="tab === 'config'" id="ai-config-panel" role="tabpanel" aria-labelledby="ai-config-tab">
      <div v-loading="loading" class="isd-card overflow-hidden">
        <div class="border-b border-line px-5 py-4">
          <h2 class="m-0 text-sm font-semibold">模型服务配置</h2>
          <p class="mb-0 mt-1 text-xs leading-5 text-ink-600">保存仅创建待启用版本。更换 Key 或模型后，当前服务会继续使用原版本，直到你确认切换。</p>
        </div>
        <div class="overflow-x-auto">
          <table class="ai-table ai-config-table" aria-label="模型服务配置">
            <thead><tr><th>配置</th><th>供应商 / 模型</th><th>API Key</th><th>版本状态</th><th class="min-w-[260px]">操作</th></tr></thead>
            <tbody>
              <tr v-for="row in visibleConfigurations" :key="row.id">
                <td><span class="font-medium text-ink-900">{{ row.name }}</span><p class="mb-0 mt-1 text-xs text-ink-600">版本 {{ row.version }}</p></td>
                <td>{{ providerName(row.provider) }}<p class="mb-0 mt-1 break-all text-xs text-ink-600">{{ row.model }}</p></td>
                <td class="whitespace-nowrap">{{ row.keySuffix ? `•••• ${row.keySuffix}` : '未配置' }}</td>
                <td>
                  <span v-if="isCurrent(row)" class="ai-label bg-brand-50 text-brand-700">当前使用</span>
                  <span v-else class="ai-label bg-gray-100 text-ink-600">待启用</span>
                  <p class="mb-0 mt-1 text-xs" :class="row.testStatus === 'FAILED' ? 'text-red-700' : row.testStatus === 'PASSED' ? 'text-emerald-700' : 'text-ink-600'">{{ testLabels[row.testStatus] || '待测试' }}</p>
                  <p v-if="currentUsesConfiguration(row) && !isCurrent(row)" class="mb-0 mt-1 text-xs text-brand-700">正在使用版本 {{ runtime.configurationVersion }}</p>
                  <p v-if="row.testStatus === 'FAILED' && row.testMessage" class="mb-0 mt-1 max-w-[240px] text-xs text-red-700">{{ row.testMessage }}</p>
                </td>
                <td>
                  <div class="flex flex-wrap items-center gap-x-3 gap-y-2">
                    <button type="button" class="ai-action" :disabled="Boolean(busy) || runtime?.credentialsReady === false" @click="openEditor(row)">编辑</button>
                    <button type="button" class="ai-action" :disabled="Boolean(busy) || runtime?.credentialsReady === false" @click="testConfiguration(row)">{{ busy === `test-${row.id}` ? '测试中…' : '测试连接' }}</button>
                    <button type="button" class="ai-action" :disabled="row.testStatus !== 'PASSED' || isCurrent(row) || Boolean(busy) || runtime?.credentialsReady === false" @click="activate(row)">设为当前</button>
                    <button type="button" class="ai-action" @click="showVersions(row)">版本记录</button>
                    <button type="button" class="ai-action" @click="showBalance(row)">查看余额</button>
                    <button type="button" class="ai-action text-ink-600" :disabled="currentUsesConfiguration(row) || Boolean(busy)" @click="archive(row)">归档</button>
                  </div>
                </td>
              </tr>
              <tr v-if="!loading && !visibleConfigurations.length"><td colspan="5" class="!py-12 text-center text-ink-600">尚无后台配置。点击“新增配置”，录入模型服务后测试并启用。</td></tr>
            </tbody>
          </table>
        </div>
        <p class="m-0 border-t border-line px-5 py-3 text-xs leading-5 text-ink-600">仅显示 Key 掩码。归档会移除凭据并保留用量历史；当前使用的配置需要先切换或停用。</p>
      </div>
    </section>

    <section v-if="tab === 'usage'" id="ai-usage-panel" role="tabpanel" aria-labelledby="ai-usage-tab">
      <div class="isd-card mb-4 p-4 sm:p-5">
        <div class="mb-4 flex flex-wrap gap-2" aria-label="用量时间范围">
          <button v-for="option in [{ value: '1', label: '今日' }, { value: '7', label: '近 7 天' }, { value: '30', label: '近 30 天' }, { value: 'custom', label: '自定义' }]" :key="option.value" type="button" class="ai-period" :class="{ selected: period === option.value }" :aria-pressed="period === option.value" @click="selectPeriod(option.value)">{{ option.label }}</button>
        </div>
        <form class="grid gap-3 sm:grid-cols-2 lg:grid-cols-4" @submit.prevent="loadUsage">
          <label class="ai-field">开始日期<input v-model="filters.from" type="date" aria-label="用量开始日期" @change="period = 'custom'" /></label>
          <label class="ai-field">结束日期<input v-model="filters.to" type="date" aria-label="用量结束日期" @change="period = 'custom'" /></label>
          <label class="ai-field">供应商<el-select v-model="filters.provider" clearable aria-label="筛选供应商" placeholder="全部供应商"><el-option v-for="item in providers" :key="item.value" :label="item.label" :value="item.value" /></el-select></label>
          <label class="ai-field">配置<el-select v-model="filters.configurationId" clearable aria-label="筛选配置" placeholder="全部配置"><el-option v-for="item in configurations" :key="item.id" :label="item.name + (item.archived ? '（已归档）' : '')" :value="item.id" /></el-select></label>
          <label class="ai-field">模型<el-input v-model="filters.model" clearable aria-label="筛选模型" placeholder="模型名称" /></label>
          <label class="ai-field">用户<el-select v-model="filters.userId" filterable clearable aria-label="筛选用户" placeholder="全部用户"><el-option v-for="item in userOptions" :key="item.id" :label="item.fullName ? `${item.fullName}（${item.username}）` : item.username" :value="item.id" /></el-select></label>
          <label class="ai-field">用途<el-select v-model="filters.purpose" clearable aria-label="筛选用途" placeholder="全部用途"><el-option v-for="(label, value) in purposes" :key="value" :label="label" :value="value" /></el-select></label>
          <label class="ai-field">调用结果<el-select v-model="filters.outcome" clearable aria-label="筛选调用结果" placeholder="全部结果"><el-option v-for="(label, value) in outcomes" :key="value" :label="label" :value="value" /></el-select></label>
          <div class="flex items-center gap-2 sm:col-span-2 lg:col-span-4"><el-button native-type="submit" type="primary" :loading="usageLoading">查询用量</el-button><el-button @click="resetFilters">重置</el-button><span class="text-xs text-ink-600">北京时间，最多查询 365 天</span></div>
        </form>
      </div>
      <div v-if="usageFailed" role="alert" class="mb-4 rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">用量读取失败，请重新查询；下方保留上次成功加载的数据。</div>
      <div v-loading="usageLoading">
        <div v-if="summary" class="isd-card mb-4 overflow-hidden">
          <div class="grid grid-cols-2 divide-x divide-line border-b border-line lg:grid-cols-4">
            <div class="p-4 sm:p-5"><p class="m-0 text-xs text-ink-600">业务调用</p><p class="mb-0 mt-2 text-2xl font-semibold tabular-nums" data-testid="usage-call-count">{{ number(summary.calls) }}</p><p class="mb-0 mt-1 text-xs text-ink-600">包含连接测试，可按用途筛选</p></div>
            <div class="p-4 sm:p-5"><p class="m-0 text-xs text-ink-600">实际上游请求</p><p class="mb-0 mt-2 text-2xl font-semibold tabular-nums">{{ number(summary.upstreamRequests) }}</p><p class="mb-0 mt-1 text-xs text-ink-600">包含工具回合与重试</p></div>
            <div class="p-4 sm:p-5"><p class="m-0 text-xs text-ink-600">输入 / 输出 Token</p><p class="mb-0 mt-2 text-lg font-semibold tabular-nums">{{ number(summary.inputTokens) }} / {{ number(summary.outputTokens) }}</p><p class="mb-0 mt-1 text-xs text-ink-600">输入中缓存命中 {{ number(summary.cachedInputTokens) }}</p></div>
            <div class="p-4 sm:p-5"><p class="m-0 text-xs text-ink-600">失败 / 进行中</p><p class="mb-0 mt-2 text-lg font-semibold tabular-nums">{{ number(summary.failedCalls) }} / {{ number(summary.pendingCalls) }}</p><p class="mb-0 mt-1 text-xs text-ink-600">{{ number(summary.unknownUsageCalls) }} 次用量未知或不完整</p></div>
          </div>
          <p class="m-0 border-b border-line px-4 py-2 text-xs leading-5 text-ink-600 sm:px-5">Token 为已知用量小计；未知或不完整记录不代表零消耗，缓存命中已包含在输入中。</p>
          <div class="flex flex-col justify-between gap-3 p-4 sm:flex-row sm:p-5">
            <div><h2 class="m-0 text-sm font-semibold">费用估算</h2><p class="mb-0 mt-1 text-xs leading-5 text-ink-600">按调用当时的单价计算，仅统计本系统消耗；不等同于供应商账单。</p></div>
            <div class="flex flex-wrap items-start gap-x-6 gap-y-2 text-right">
              <div v-for="cost in knownCosts.filter(item => item.currency !== 'UNCONFIGURED')" :key="cost.currency"><p class="m-0 text-lg font-semibold tabular-nums">{{ cost.currency }} {{ money(cost.estimatedCost) }}</p><p class="mb-0 mt-1 text-xs text-ink-600">已知费用小计</p></div>
              <p v-if="!knownCosts.some(item => item.currency !== 'UNCONFIGURED')" class="m-0 text-sm text-ink-600">暂无可计算的费用</p>
              <p v-if="unknownCosts" class="m-0 w-full text-xs text-amber-800">{{ number(unknownCosts) }} 次费用未知或未配置单价，未计入小计</p>
            </div>
          </div>
        </div>
        <div v-if="summary" class="mb-4 grid gap-4 xl:grid-cols-[minmax(0,1.35fr)_minmax(0,1fr)]">
          <section class="isd-card min-w-0 p-5" aria-label="每日调用趋势">
            <h2 class="m-0 text-sm font-semibold">每日调用趋势</h2>
            <p class="mb-4 mt-1 text-xs text-ink-600">业务调用次数；悬停查看当日 Token 和请求数</p>
            <div v-if="daily.some(item => item.calls)" class="overflow-x-auto pb-1">
              <div class="ai-chart" :style="{ minWidth: `${Math.max(240, daily.length * 6)}px` }">
                <div v-for="(item, index) in daily" :key="item.date" class="ai-chart-column" :title="`${item.date}：${item.calls} 次调用，${item.upstreamRequests} 次上游请求，输入 ${item.inputTokens ?? '未知'} / 输出 ${item.outputTokens ?? '未知'} Token`">
                  <div class="ai-chart-bar" :style="{ height: `${Math.max(item.calls ? 3 : 0, (item.calls || 0) / dailyMaximum * 115)}px` }" role="img" :aria-label="`${item.date}，${item.calls} 次调用`" />
                  <span v-if="index % Math.max(1, Math.ceil(daily.length / 7)) === 0 || index === daily.length - 1" class="ai-chart-date">{{ shortDate(item.date) }}</span>
                </div>
              </div>
            </div>
            <p v-else class="py-10 text-center text-xs text-ink-600">所选时间范围暂无调用</p>
          </section>
          <section class="isd-card min-w-0 overflow-hidden">
            <h2 class="m-0 border-b border-line px-5 py-4 text-sm font-semibold">用户消耗</h2>
            <div class="max-h-[265px] overflow-auto"><table class="ai-table" aria-label="用户用量排名"><thead><tr><th>用户</th><th>调用</th><th>输入 / 输出 Token</th></tr></thead><tbody><tr v-for="row in usageUsers" :key="row.userId"><td>{{ row.fullName || row.username || `用户 ${row.userId}` }}</td><td>{{ number(row.calls) }}</td><td>{{ number(row.inputTokens) }} / {{ number(row.outputTokens) }}</td></tr><tr v-if="!usageUsers.length"><td colspan="3" class="!py-8 text-center text-ink-600">暂无用户消耗</td></tr></tbody></table></div>
          </section>
        </div>
        <section v-if="summary" class="isd-card overflow-hidden">
          <div class="border-b border-line px-5 py-4"><h2 class="m-0 text-sm font-semibold">调用明细</h2><p class="mb-0 mt-1 text-xs leading-5 text-ink-600">只展示调用元数据，不展示员工的问题与回答。响应超时后，用量仍可能由后台补记。</p></div>
          <div class="overflow-x-auto"><table class="ai-table ai-call-table" aria-label="AI 调用明细"><thead><tr><th>时间 / 用户</th><th>用途</th><th>服务 / 模型</th><th>业务 / 上游结果</th><th>请求</th><th>输入 / 缓存 / 输出 Token</th><th>估算费用</th></tr></thead><tbody>
            <tr v-for="row in calls.items" :key="row.id"><td class="whitespace-nowrap">{{ dateTime(row.createdAt) }}<p class="mb-0 mt-1 text-xs text-ink-600">{{ row.fullName || row.username || `用户 ${row.userId}` }}</p></td><td>{{ purposes[row.purpose] || row.purpose }}</td><td>{{ row.configurationName || providerName(row.provider) }}<p class="mb-0 mt-1 break-all text-xs text-ink-600">{{ row.actualModel || row.model }}</p><p class="mb-0 mt-1 text-xs text-ink-600">{{ row.configurationVersion ? `版本 ${row.configurationVersion}` : '部署配置' }} · {{ row.keySuffix ? `•••• ${row.keySuffix}` : '无 Key' }}</p></td><td>{{ outcomes[row.businessOutcome] || row.businessOutcome }}<p class="mb-0 mt-1 text-xs text-ink-600">上游{{ upstreamOutcomes[row.upstreamOutcome] || row.upstreamOutcome }}</p></td><td>{{ number(row.upstreamRequests) }}</td><td class="whitespace-nowrap">{{ tokenValue(row, 'inputTokens') }} / {{ tokenValue(row, 'cachedInputTokens') }} / {{ tokenValue(row, 'outputTokens') }}<p v-if="!row.usageComplete" class="mb-0 mt-1 text-xs text-amber-800">用量未知或不完整</p></td><td class="whitespace-nowrap">{{ row.estimatedCost == null ? '未知 / 未配置' : `${row.currency} ${money(row.estimatedCost)}` }}</td></tr>
            <tr v-if="!calls.items?.length"><td colspan="7" class="!py-10 text-center text-ink-600">所选条件下暂无调用记录</td></tr>
          </tbody></table></div>
          <div class="flex flex-wrap items-center justify-between gap-3 border-t border-line px-5 py-3"><span class="text-xs text-ink-600">共 {{ number(calls.total) }} 条；Token 统计自功能上线后开始，历史数据不补估。</span><el-pagination :current-page="calls.page" :page-size="calls.size || 10" :total="calls.total" layout="prev, pager, next" small @current-change="changePage" /></div>
        </section>
      </div>
    </section>

    <el-dialog v-model="editorVisible" :title="editingId ? '编辑模型服务配置' : '新增模型服务配置'" width="min(620px, calc(100vw - 32px))" :close-on-click-modal="false" @closed="clearSecret">
      <p class="mb-5 mt-0 rounded-md bg-brand-50 px-3 py-2 text-xs leading-5 text-brand-700">保存后需要测试并确认启用。当前服务不会因保存而切换。</p>
      <form id="ai-configuration-form" class="grid gap-4 sm:grid-cols-2" autocomplete="off" @submit.prevent="saveConfiguration">
        <label class="ai-field">配置名称<el-input v-model="editor.name" maxlength="80" aria-label="配置名称" placeholder="例如：日常服务台" /></label>
        <label class="ai-field">供应商<el-select v-model="editor.provider" aria-label="配置供应商" @change="changeProvider"><el-option v-for="item in providers" :key="item.value" :value="item.value" :label="item.label" /></el-select></label>
        <label class="ai-field sm:col-span-2">接口地址<el-input v-model="editor.baseUrl" maxlength="512" aria-label="模型接口地址" placeholder="填写服务的 Base URL，不包含 /chat/completions" /><span class="text-xs font-normal leading-5 text-ink-600">使用完整 Base URL；通义千问请核对业务空间、地域与 Key 是否匹配。自定义域名需由部署管理员允许。</span></label>
        <label class="ai-field">模型<el-input v-model="editor.model" maxlength="128" aria-label="模型名称" placeholder="服务商提供的模型标识" /></label>
        <label class="ai-field">{{ editingId ? '替换 API Key（可选）' : 'API Key' }}<el-input v-model="editor.apiKey" type="password" maxlength="4096" autocomplete="new-password" aria-label="模型 API Key" :placeholder="editingId ? '留空保留已有 Key' : '请输入 API Key'" /><span class="text-xs font-normal text-ink-600">{{ editingId ? '已有 Key 不会回显，留空即可保留。' : '加密保存，保存后仅显示掩码。' }}</span></label>
        <div class="border-t border-line pt-4 sm:col-span-2"><h3 class="m-0 text-sm font-medium">费用估算单价（可选）</h3><p class="mb-0 mt-1 text-xs leading-5 text-ink-600">按每百万 Token 填写合同或供应商单价；未填写时仍可统计用量，不显示为免费。</p></div>
        <label class="ai-field">计费币种<el-select v-model="editor.currency" clearable aria-label="计费币种" placeholder="未配置"><el-option label="人民币 CNY" value="CNY" /><el-option label="美元 USD" value="USD" /></el-select></label>
        <label class="ai-field">未缓存输入单价<el-input v-model="editor.inputPrice" inputmode="decimal" aria-label="未缓存输入单价" placeholder="每百万 Token" /></label>
        <label class="ai-field">缓存命中输入单价<el-input v-model="editor.cachedInputPrice" inputmode="decimal" aria-label="缓存输入单价" placeholder="每百万 Token" /></label>
        <label class="ai-field">输出单价<el-input v-model="editor.outputPrice" inputmode="decimal" aria-label="输出单价" placeholder="每百万 Token" /></label>
      </form>
      <template #footer><el-button @click="editorVisible = false; clearSecret()">取消</el-button><el-button type="primary" :loading="saving" :disabled="runtime?.credentialsReady === false" @click="saveConfiguration">保存待启用版本</el-button></template>
    </el-dialog>

    <el-dialog v-model="versionsVisible" :title="`${versionsName} · 版本记录`" width="min(760px, calc(100vw - 32px))">
      <p class="mb-4 mt-0 text-xs leading-5 text-ink-600">历史单价与 Key 版本保留在各自的调用记录中。可将已通过测试的版本重新设为当前服务。</p>
      <div v-loading="versionsLoading" class="overflow-x-auto"><table class="ai-table" aria-label="配置版本记录"><thead><tr><th>版本</th><th>模型 / Key</th><th>连接测试</th><th>操作</th></tr></thead><tbody><tr v-for="row in versionRows" :key="row.version"><td>版本 {{ row.version }}<span v-if="isCurrent(row)" class="ml-1 ai-label bg-brand-50 text-brand-700">当前</span></td><td>{{ row.model }}<p class="mb-0 mt-1 text-xs text-ink-600">•••• {{ row.keySuffix }}</p></td><td>{{ testLabels[row.testStatus] }}<p class="mb-0 mt-1 text-xs text-ink-600">{{ dateTime(row.testedAt) }}</p></td><td><button type="button" class="ai-action" :disabled="row.testStatus !== 'PASSED' || isCurrent(row) || row.archived || Boolean(busy) || runtime?.credentialsReady === false" @click="activate(row)">设为当前</button></td></tr></tbody></table></div>
    </el-dialog>

    <el-dialog v-model="balanceVisible" :title="`${balanceTarget?.name || ''} · 官方账户余额`" width="min(560px, calc(100vw - 32px))">
      <div v-loading="balanceLoading">
        <p class="mb-4 mt-0 text-xs leading-5 text-ink-600">按此配置最新保存版本的 Key 查询。余额属于供应商账户，可能包含其他系统的消耗；多个 Key 的余额不能相加。</p>
        <template v-if="balance">
          <p v-if="balance.stale" role="status" class="rounded-md bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-800">当前显示上次查询结果，可能已过期。{{ balance.message }}</p>
          <p v-else-if="balance.message" class="text-sm leading-6 text-ink-600">{{ balance.message }}</p>
          <div v-for="item in balance.balances || []" :key="item.currency" class="mb-3 rounded-md border border-line p-4"><p class="m-0 text-xs text-ink-600">账户余额（{{ item.currency }}）</p><p class="mb-0 mt-2 text-2xl font-semibold tabular-nums">{{ money(item.totalBalance) }}</p><dl class="mb-0 mt-3 flex flex-wrap gap-x-6 gap-y-2 text-xs text-ink-600"><div><dt class="inline">赠送余额 </dt><dd class="m-0 inline">{{ money(item.grantedBalance) }}</dd></div><div><dt class="inline">充值余额 </dt><dd class="m-0 inline">{{ money(item.toppedUpBalance) }}</dd></div></dl></div>
          <p v-if="balance.supported && !balance.balances?.length" class="text-sm text-ink-600">尚无可显示的余额，请点击“刷新余额”。</p>
          <p v-if="balance.checkedAt" class="text-xs text-ink-600">查询时间：{{ dateTime(balance.checkedAt) }}</p>
          <a v-if="safePortal(balance.consoleUrl)" :href="safePortal(balance.consoleUrl)" target="_blank" rel="noopener noreferrer" class="ai-action inline-block py-2">前往供应商官方控制台</a>
        </template>
      </div>
      <template #footer><el-button @click="balanceVisible = false">关闭</el-button><el-button v-if="balance?.supported" type="primary" :loading="balanceLoading" @click="refreshBalance">刷新余额</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ai-admin { min-width: 0; }
.ai-tab { padding: 0 0 12px; border-bottom: 2px solid transparent; color: #6b7280; font-size: 13px; }
.ai-tab.active { border-color: #2563eb; color: #1d4ed8; font-weight: 600; }
.ai-tab:focus-visible, .ai-action:focus-visible, .ai-period:focus-visible { outline: 2px solid #2563eb; outline-offset: 3px; }
.ai-label { display: inline-block; padding: 2px 7px; border-radius: 4px; font-size: 11px; line-height: 18px; white-space: nowrap; }
.ai-table { width: 100%; border-collapse: collapse; text-align: left; font-size: 13px; }
.ai-table th { padding: 12px 18px; background: #f8fafc; color: #6b7280; font-size: 12px; font-weight: 500; white-space: nowrap; }
.ai-table td { padding: 14px 18px; border-top: 1px solid #eef0f4; vertical-align: top; line-height: 20px; }
.ai-table tbody tr:hover { background: #f8faff; }
.ai-config-table { min-width: 940px; }
.ai-config-table td:first-child { min-width: 130px; }
.ai-config-table td:nth-child(2) { min-width: 140px; }
.ai-config-table td:nth-child(4) { min-width: 110px; }
.ai-call-table { min-width: 1180px; }
.ai-call-table td:nth-child(2) { min-width: 70px; }
.ai-call-table td:nth-child(3) { min-width: 150px; }
.ai-call-table td:nth-child(4) { min-width: 100px; }
.ai-action { color: #1d4ed8; font-size: 12px; white-space: nowrap; }
.ai-action:hover { text-decoration: underline; }
.ai-action:disabled { color: #9ca3af; cursor: not-allowed; text-decoration: none; }
.ai-field { display: flex; flex-direction: column; gap: 7px; min-width: 0; color: #4b5563; font-size: 12px; }
.ai-field > input { box-sizing: border-box; width: 100%; min-width: 0; height: 32px; padding: 4px 10px; border: 1px solid #dcdfe6; border-radius: 6px; background: #fff; color: #1f2937; font: inherit; }
.ai-field > input:focus { outline: 1px solid #2563eb; border-color: #2563eb; }
.ai-period { padding: 6px 10px; border: 1px solid #e5e7eb; border-radius: 5px; color: #6b7280; font-size: 12px; }
.ai-period.selected { border-color: #93b4f5; background: #eff6ff; color: #1d4ed8; }
.ai-chart { display: flex; align-items: flex-end; gap: 2px; height: 152px; padding-bottom: 23px; border-bottom: 1px solid #e5e7eb; box-sizing: border-box; }
.ai-chart-column { position: relative; display: flex; flex: 1; min-width: 0; align-items: flex-end; justify-content: center; height: 100%; }
.ai-chart-bar { width: 100%; max-width: 36px; border-radius: 3px 3px 0 0; background: #3b82f6; }
.ai-chart-date { position: absolute; bottom: -22px; left: 50%; transform: translateX(-50%); color: #6b7280; font-size: 10px; white-space: nowrap; }
</style>
