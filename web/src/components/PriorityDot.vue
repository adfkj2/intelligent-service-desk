<script setup>
import { computed } from 'vue'
import { TICKET_PRIORITY } from '../constants/ticket'

const props = defineProps({
  priority: { type: String, default: 'MEDIUM' }
})

const meta = computed(() => TICKET_PRIORITY[props.priority] || { label: props.priority, color: '#9CA3AF' })
</script>

<template>
  <span class="inline-flex items-center gap-1.5 text-[13px] text-ink-900">
    <span class="h-2 w-2 shrink-0 rounded-full" :style="{ backgroundColor: meta.color }" />
    {{ meta.label }}
  </span>
</template>
