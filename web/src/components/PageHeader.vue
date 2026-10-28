<script setup>
defineProps({
  title: { type: String, required: true },
  description: { type: String, default: '' }
})
</script>

<template>
  <div class="mb-5 flex items-start justify-between gap-4">
    <div>
      <h1 class="m-0 text-[20px] font-semibold leading-7 text-ink-900">{{ title }}</h1>
      <p v-if="description" class="mt-1 mb-0 text-[13px] text-ink-600">{{ description }}</p>
    </div>
    <div class="flex shrink-0 items-center gap-2">
      <slot name="actions" />
    </div>
  </div>
</template>
