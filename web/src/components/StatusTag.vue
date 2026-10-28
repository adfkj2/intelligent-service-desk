<script setup>
import { computed } from 'vue'
import { TICKET_STATUS } from '../constants/ticket'

const props = defineProps({
  status: { type: String, required: true },
  size: { type: String, default: 'default' }
})

const meta = computed(() => TICKET_STATUS[props.status] || { label: props.status, type: 'info' })
</script>

<template>
  <el-tag :type="meta.type" :size="size" effect="light" round>{{ meta.label }}</el-tag>
</template>
