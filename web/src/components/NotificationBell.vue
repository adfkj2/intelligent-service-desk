<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Bell, CheckCheck, Inbox } from 'lucide-vue-next'
import { notificationApi } from '../api/notification'

/**
 * 顶栏消息铃铛。
 *
 * 数据来源是后端 outbox 异步投递出来的 notification 表，所以这里只做"拉"，不做推送：
 * 通知不是聊天，30 秒一次轮询足够；页面不可见时跳过，避免后台标签页空跑接口。
 *
 * 未读数与列表分开取：铃铛只需要一个数字（轻），点开才拉列表（重）。
 */
const POLL_INTERVAL_MS = 30_000
const PAGE_SIZE = 20

const TYPE_LABEL = {
  TICKET_STATUS_CHANGED: '状态变更',
  TICKET_ASSIGNED: '指派'
}

const router = useRouter()
const open = ref(false)
const loading = ref(false)
const markingAll = ref(false)
const unread = ref(0)
const items = ref([])

let pollTimer = null

async function refreshUnread() {
  if (document.hidden) {
    return
  }
  try {
    const data = await notificationApi.unreadCount()
    const next = data?.count ?? 0
    const changed = next !== unread.value
    unread.value = next
    // 面板正开着时只更新徽标，用户看不到新内容：数量变了就顺手把列表刷新一遍
    if (open.value && changed) {
      await loadList()
    }
  } catch (error) {
    // 静默失败：silent 已关掉全局提示，下一轮轮询会自愈
    console.debug('未读数刷新失败', error)
  }
}

async function loadList() {
  loading.value = true
  try {
    items.value = (await notificationApi.list(PAGE_SIZE)) ?? []
  } catch (error) {
    console.error('加载站内信失败', error)
  } finally {
    loading.value = false
  }
}

function handleShow() {
  // 打开面板时同时刷新列表与未读数：可能刚收到新通知（最多滞后一个轮询周期）
  loadList()
  refreshUnread()
}

async function openItem(item) {
  open.value = false
  if (!item.read) {
    // 先本地置读，避免网络往返期间重复点击；服务端失败也不阻塞跳转，下次刷新会纠正
    item.read = true
    unread.value = Math.max(0, unread.value - 1)
    try {
      await notificationApi.markRead(item.id)
    } catch (error) {
      console.error('标记已读失败', error)
    }
  }
  if (item.ticketId) {
    router.push({ name: 'ticket-detail', params: { id: item.ticketId } })
  }
}

async function markAllRead() {
  if (unread.value === 0 || markingAll.value) {
    return
  }
  markingAll.value = true
  try {
    await notificationApi.markAllRead()
    items.value = items.value.map((item) => ({ ...item, read: true }))
    unread.value = 0
  } catch (error) {
    console.error('全部已读失败', error)
  } finally {
    markingAll.value = false
  }
}

/** 后端返回 LocalDateTime 字符串（无时区），按本地时间解析；超过一周直接显示日期 */
function relativeTime(value) {
  if (!value) {
    return ''
  }
  const time = new Date(value).getTime()
  if (Number.isNaN(time)) {
    return value
  }
  const diff = Date.now() - time
  if (diff < 60_000) {
    return '刚刚'
  }
  if (diff < 3_600_000) {
    return `${Math.floor(diff / 60_000)} 分钟前`
  }
  if (diff < 86_400_000) {
    return `${Math.floor(diff / 3_600_000)} 小时前`
  }
  if (diff < 7 * 86_400_000) {
    return `${Math.floor(diff / 86_400_000)} 天前`
  }
  return value.slice(0, 10)
}

function handleVisibility() {
  if (!document.hidden) {
    refreshUnread()
  }
}

onMounted(() => {
  refreshUnread()
  pollTimer = window.setInterval(refreshUnread, POLL_INTERVAL_MS)
  document.addEventListener('visibilitychange', handleVisibility)
})

onBeforeUnmount(() => {
  if (pollTimer) {
    window.clearInterval(pollTimer)
  }
  pollTimer = null
  document.removeEventListener('visibilitychange', handleVisibility)
})
</script>

<template>
  <el-popover
    v-model:visible="open"
    placement="bottom-end"
    :width="360"
    trigger="click"
    popper-class="notification-popper"
    @show="handleShow"
  >
    <template #reference>
      <button
        type="button"
        class="flex h-8 w-8 cursor-pointer items-center justify-center rounded-md text-ink-600 transition hover:bg-brand-50 hover:text-brand-600"
        title="消息通知"
      >
        <el-badge :value="unread" :max="99" :hidden="unread === 0" :offset="[0, 2]">
          <Bell :size="18" />
        </el-badge>
      </button>
    </template>

    <div class="-my-2">
      <div class="flex items-center justify-between border-b border-line px-1 pb-2.5 pt-1">
        <div class="flex items-center gap-1.5 text-[13px] font-medium text-ink-900">
          消息通知
          <span v-if="unread > 0" class="rounded-full bg-brand-50 px-1.5 py-px text-[11px] text-brand-700">
            {{ unread }} 条未读
          </span>
        </div>
        <button
          type="button"
          class="flex cursor-pointer items-center gap-1 border-0 bg-transparent p-0 text-[12px] text-brand-600 transition hover:text-brand-700 disabled:cursor-not-allowed disabled:text-ink-400"
          :disabled="unread === 0 || markingAll"
          @click="markAllRead"
        >
          <CheckCheck :size="14" />
          全部已读
        </button>
      </div>

      <div v-loading="loading" class="max-h-[360px] min-h-[140px] overflow-y-auto py-1">
        <button
          v-for="item in items"
          :key="item.id"
          type="button"
          class="flex w-full cursor-pointer items-start gap-2.5 rounded-md border-0 bg-transparent px-2 py-2.5 text-left transition hover:bg-canvas"
          @click="openItem(item)"
        >
          <span
            class="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full"
            :class="item.read ? 'bg-transparent' : 'bg-brand-600'"
          />
          <span class="min-w-0 flex-1">
            <span class="flex items-center gap-2">
              <span
                class="truncate text-[13px]"
                :class="item.read ? 'text-ink-600' : 'font-medium text-ink-900'"
              >
                {{ item.title }}
              </span>
              <span class="shrink-0 rounded bg-canvas px-1 py-px text-[10px] text-ink-400">
                {{ TYPE_LABEL[item.type] || item.type }}
              </span>
            </span>
            <span class="mt-1 block text-[12px] leading-5 text-ink-600 line-clamp-2">{{ item.content }}</span>
            <span class="mt-1 block text-[11px] text-ink-400">{{ relativeTime(item.createdAt) }}</span>
          </span>
        </button>

        <div
          v-if="!loading && items.length === 0"
          class="flex flex-col items-center justify-center py-8 text-ink-400"
        >
          <Inbox :size="22" />
          <p class="m-0 mt-2 text-[12px]">暂无通知</p>
        </div>
      </div>

      <p class="m-0 border-t border-line pt-2 text-center text-[11px] text-ink-400">
        仅展示最近 {{ PAGE_SIZE }} 条
      </p>
    </div>
  </el-popover>
</template>
