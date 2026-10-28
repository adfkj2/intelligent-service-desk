<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Copy, Mail, Phone, RefreshCw } from 'lucide-vue-next'
import { authApi } from '../api/auth'
import { copyText } from '../utils/clipboard'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value)
})

const loading = ref(false)
const failed = ref(false)
const contacts = ref([])

async function load() {
  loading.value = true
  failed.value = false
  try {
    contacts.value = (await authApi.adminContacts()) || []
  } catch (error) {
    // 拦截器已提示；这里只负责切换到“重试”状态
    console.error('加载管理员联系方式失败', error)
    failed.value = true
    contacts.value = []
  } finally {
    loading.value = false
  }
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      load()
    }
  }
)

function copy(value) {
  copyText(value)
    .then(() => ElMessage.success('已复制'))
    .catch((error) => {
      console.error('复制失败', error)
      ElMessage.error('复制失败，请手动选择文本')
    })
}
</script>

<template>
  <el-dialog v-model="visible" title="联系管理员重置密码" width="460px">
    <p class="m-0 text-[13px] leading-6 text-ink-600">
      员工无法自助找回密码。请通过以下方式联系管理员，由管理员为你生成一次性临时密码。
    </p>

    <div v-if="loading" class="mt-4 space-y-3">
      <el-skeleton animated :rows="3" />
    </div>

    <div v-else-if="failed" class="mt-4">
      <el-alert type="error" :closable="false" title="联系方式加载失败" description="请检查网络后重试。" />
      <el-button class="mt-3" :icon="RefreshCw" @click="load">重新加载</el-button>
    </div>

    <el-empty
      v-else-if="contacts.length === 0"
      description="管理员暂未配置联系方式，请联系 IT 服务台"
      :image-size="72"
    />

    <div v-else class="mt-4 space-y-2.5">
      <div
        v-for="contact in contacts"
        :key="contact.fullName + contact.phone + contact.email"
        class="rounded-lg border border-line bg-canvas/70 px-3.5 py-3"
      >
        <p class="m-0 text-[13px] font-medium text-ink-900">{{ contact.fullName }}</p>

        <div v-if="contact.phone" class="mt-2 flex items-center justify-between gap-2">
          <a
            :href="`tel:${contact.phone}`"
            class="flex items-center gap-1.5 text-[12px] text-brand-600 hover:text-brand-700"
          >
            <Phone :size="13" />
            {{ contact.phone }}
          </a>
          <el-button link type="primary" size="small" :icon="Copy" @click="copy(contact.phone)">复制</el-button>
        </div>

        <div v-if="contact.email" class="mt-1.5 flex items-center justify-between gap-2">
          <a
            :href="`mailto:${contact.email}`"
            class="flex items-center gap-1.5 break-all text-[12px] text-brand-600 hover:text-brand-700"
          >
            <Mail :size="13" />
            {{ contact.email }}
          </a>
          <el-button link type="primary" size="small" :icon="Copy" @click="copy(contact.email)">复制</el-button>
        </div>
      </div>
    </div>

    <p class="m-0 mt-4 rounded-md bg-brand-50 px-3 py-2 text-[12px] leading-5 text-brand-700">
      管理员会为你生成一次性临时密码；使用临时密码首次登录后，系统会要求你立即修改密码。
    </p>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>
