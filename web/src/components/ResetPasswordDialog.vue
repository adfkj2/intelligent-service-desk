<script setup>
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Copy } from 'lucide-vue-next'
import { copyText } from '../utils/clipboard'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  payload: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue'])

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value)
})

function copy() {
  if (!props.payload?.temporaryPassword) {
    return
  }
  copyText(props.payload.temporaryPassword)
    .then(() => ElMessage.success('临时密码已复制'))
    .catch((error) => {
      console.error('复制临时密码失败', error)
      ElMessage.error('复制失败，请手动选择文本')
    })
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="密码已重置"
    width="460px"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
  >
    <p class="m-0 text-[13px] leading-6 text-ink-600">
      账号
      <span class="font-medium text-ink-900">{{ payload?.username }}</span>
      的临时密码如下。请立即通过线下可信渠道转告对方。
    </p>

    <div class="mt-4 rounded-lg border border-brand-100 bg-brand-50/70 px-4 py-4">
      <p class="m-0 text-[11px] tracking-wide text-brand-700">一次性临时密码</p>
      <div class="mt-2 flex items-center justify-between gap-3">
        <code class="select-all font-mono text-[20px] font-semibold tracking-[0.12em] text-ink-900">
          {{ payload?.temporaryPassword }}
        </code>
        <el-button type="primary" plain size="small" :icon="Copy" @click="copy">复制</el-button>
      </div>
    </div>

    <el-alert
      class="mt-4"
      type="warning"
      :closable="false"
      title="关闭后无法再次查看"
      description="后端只保存加密后的密码，不保留明文。若未复制，只能重新重置一次。"
    />

    <p class="m-0 mt-3 text-[12px] leading-5 text-ink-400">
      对方当前登录会话已失效；使用临时密码登录后，必须先修改密码才能进入业务页面。
    </p>

    <template #footer>
      <el-button type="primary" @click="visible = false">我已复制并关闭</el-button>
    </template>
  </el-dialog>
</template>
