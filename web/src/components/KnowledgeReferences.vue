<script setup>
import { knowledgeLink } from '../utils/ticket'
defineProps({ references: { type: Array, default: () => [] } })
defineEmits(['navigate'])
</script>

<template>
  <div v-if="references.length" class="mt-3 space-y-2 border-t border-line pt-3">
    <p class="m-0 text-xs font-medium text-ink-600">参考资料</p>
    <router-link
      v-for="reference in references" :key="reference.id" :to="knowledgeLink(reference.id)"
      class="block rounded border border-line px-3 py-2 text-[13px] text-brand-700 hover:border-brand-500"
      @click="$emit('navigate')"
    >
      {{ reference.title }}
      <span v-if="reference.environment" class="mt-1 block text-xs text-ink-600">适用环境：{{ reference.environment }}</span>
      <span v-if="reference.reviewRequired" class="mt-1 block text-xs text-amber-800">此条目已到复核日期，请先核实步骤是否仍然适用。</span>
      <span v-if="reference.excerpt" class="mt-1 block text-xs leading-5 text-ink-600">{{ reference.excerpt }}</span>
    </router-link>
  </div>
</template>
