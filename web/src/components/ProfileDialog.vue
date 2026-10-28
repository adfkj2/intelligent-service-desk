<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { authApi } from '../api/auth'
import { useUserStore } from '../stores/user'
import { ROLE_LABEL, ROLE_TAG_TYPE } from '../constants/ticket'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

const store = useUserStore()
const saving = ref(false)
const form = reactive({ phone: '', email: '' })

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value)
})

watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      return
    }
    form.phone = store.user?.phone || ''
    form.email = store.user?.email || ''
  }
)

function validate() {
  const phone = form.phone.trim()
  const email = form.email.trim()
  if (phone && !/^[0-9+\-\s]{5,20}$/.test(phone)) {
    ElMessage.warning('手机号格式不正确，只允许数字、+、-、空格，长度 5-20')
    return null
  }
  if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    ElMessage.warning('邮箱格式不正确')
    return null
  }
  if (store.isAdmin && !phone && !email) {
    ElMessage.warning('管理员请至少填写手机号或邮箱，方便忘记密码的用户联系你')
    return null
  }
  return { phone, email }
}

async function save() {
  const payload = validate()
  if (!payload) {
    return
  }
  saving.value = true
  try {
    const updated = await authApi.updateContact(payload)
    store.setUser(updated)
    ElMessage.success('联系方式已保存')
    visible.value = false
  } catch (error) {
    console.error('保存联系方式失败', error)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="个人资料" width="460px">
    <div class="rounded-lg border border-line bg-canvas/70 px-3.5 py-3">
      <div class="flex items-center justify-between">
        <span class="text-[12px] text-ink-400">用户名</span>
        <span class="text-[13px] font-medium text-ink-900">{{ store.user?.username }}</span>
      </div>
      <div class="mt-2 flex items-center justify-between">
        <span class="text-[12px] text-ink-400">角色</span>
        <el-tag :type="ROLE_TAG_TYPE[store.role] || 'info'" size="small" effect="plain">
          {{ ROLE_LABEL[store.role] || store.role }}
        </el-tag>
      </div>
    </div>

    <el-form label-position="top" class="mt-4" @submit.prevent>
      <el-form-item label="手机号">
        <el-input v-model="form.phone" maxlength="20" placeholder="例如 13800000000，用于员工忘记密码时联系" />
      </el-form-item>
      <el-form-item label="邮箱">
        <el-input v-model="form.email" maxlength="100" placeholder="例如 it-support@example.com" />
      </el-form-item>
    </el-form>

    <p class="m-0 text-[12px] leading-5 text-ink-400">
      手机号与邮箱会展示在登录页的“忘记密码”弹窗中，仅对启用中的管理员生效。
      <span v-if="store.isAdmin">管理员至少需要填写一项。</span>
    </p>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>
