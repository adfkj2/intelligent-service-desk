<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { BookOpen, ClipboardList, LayoutDashboard, LogOut, Settings2, Sparkles, User, Users } from 'lucide-vue-next'
import { useUserStore } from '../stores/user'
import { ROLE_LABEL, ROLE_TAG_TYPE } from '../constants/ticket'
import AiDrawer from '../components/AiDrawer.vue'
import NotificationBell from '../components/NotificationBell.vue'
import ProfileDialog from '../components/ProfileDialog.vue'

const route = useRoute()
const router = useRouter()
const store = useUserStore()
const aiVisible = ref(false)
const profileVisible = ref(false)
const aiTicketId = computed(() => route.name === 'ticket-detail' ? Number(route.params.id) : null)
function openAi() { aiVisible.value = true }
onMounted(() => window.addEventListener('isd:open-ai', openAi))
onBeforeUnmount(() => window.removeEventListener('isd:open-ai', openAi))

const menus = computed(() => {
  const items = [
    { name: 'workbench', label: '工作台', icon: LayoutDashboard },
    { name: 'tickets', label: store.isStaff ? '工单池' : '我的工单', icon: ClipboardList },
    { name: 'kb', label: '知识库', icon: BookOpen }
  ]
  if (store.isAdmin) {
    items.push({ name: 'admin-users', label: '用户管理', icon: Users })
    items.push({ name: 'admin-ai', label: 'AI 配置与用量', icon: Settings2 })
  }
  return items
})

// 详情页与新建页都归属"工单"这一栏
const activeName = computed(() => {
  if (route.name === 'ticket-detail' || route.name === 'ticket-create') {
    return 'tickets'
  }
  return route.name
})

const pageTitle = computed(() => route.meta.title || '工作台')

async function handleLogout() {
  try {
    await store.logout()
  } catch (error) {
    console.error('退出登录失败', error)
  }
  router.replace({ name: 'login' })
}

function handleCommand(command) {
  if (command === 'profile') {
    profileVisible.value = true
    return
  }
  if (command === 'logout') {
    handleLogout()
  }
}
</script>

<template>
  <div class="flex h-screen overflow-hidden bg-canvas">
    <aside class="hidden w-[190px] shrink-0 flex-col bg-[#111827] md:flex xl:w-[220px]">
      <div class="flex h-14 items-center gap-2.5 border-b border-white/5 px-5">
        <div class="flex h-7 w-7 items-center justify-center rounded-md bg-brand-600 text-[13px] font-semibold text-white">
          IS
        </div>
        <div class="leading-tight">
          <p class="m-0 text-[13px] font-medium text-white">ISD 智能服务台</p>
          <p class="m-0 text-[10px] tracking-wide text-gray-500">IT SERVICE DESK</p>
        </div>
      </div>

      <nav class="flex-1 space-y-1 px-2.5 py-4">
        <router-link
          v-for="item in menus"
          :key="item.name"
          :to="{ name: item.name }"
          class="relative flex items-center gap-2.5 rounded-md px-3 py-2.5 text-[13px] transition"
          :class="
            activeName === item.name
              ? 'bg-white/10 text-white'
              : 'text-gray-400 hover:bg-white/5 hover:text-gray-100'
          "
        >
          <span
            v-if="activeName === item.name"
            class="absolute left-0 top-1/2 h-4 w-[3px] -translate-y-1/2 rounded-r bg-brand-500"
          />
          <component :is="item.icon" :size="16" />
          {{ item.label }}
        </router-link>
      </nav>

      <div class="border-t border-white/5 px-4 py-3">
        <button
          type="button"
          class="flex w-full cursor-pointer items-center gap-2 rounded-md px-1 py-1.5 text-left text-[12px] text-gray-400 transition hover:text-white"
          @click="aiVisible = true"
        >
          <Sparkles :size="14" class="text-brand-500" />
          试试 AI 助手
        </button>
        <p class="m-0 mt-2 text-[11px] text-gray-500">让每一次报障都有回应</p>
      </div>
    </aside>

    <div class="flex min-w-0 flex-1 flex-col">
      <header class="flex h-14 shrink-0 items-center justify-between gap-2 border-b border-line bg-white px-4 sm:px-6">
        <div class="flex items-center gap-2 text-[13px]">
          <span class="text-ink-400">ISD</span>
          <span class="text-ink-400">/</span>
          <span class="font-medium text-ink-900">{{ pageTitle }}</span>
        </div>

        <div class="flex items-center gap-3">
          <NotificationBell />
          <el-tag :type="ROLE_TAG_TYPE[store.role] || 'info'" size="small" effect="plain">
            {{ ROLE_LABEL[store.role] || store.role }}
          </el-tag>

          <el-dropdown trigger="click" @command="handleCommand">
            <span class="flex cursor-pointer items-center gap-2 text-[13px] text-ink-900">
              <span
                class="flex h-7 w-7 items-center justify-center rounded-full bg-brand-50 text-[12px] font-medium text-brand-700"
              >
                {{ store.displayName.slice(0, 1) }}
              </span>
              <span class="hidden sm:inline">{{ store.displayName }}</span>
              <el-icon class="text-ink-400"><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">
                  <User :size="14" class="mr-1.5 inline" />
                  个人资料
                </el-dropdown-item>
                <el-dropdown-item command="logout" divided>
                  <LogOut :size="14" class="mr-1.5 inline" />
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <nav class="flex shrink-0 gap-1 overflow-x-auto border-b border-line bg-white px-3 py-2 md:hidden" aria-label="主导航">
        <router-link v-for="item in menus" :key="item.name" :to="{ name: item.name }" class="shrink-0 rounded px-3 py-2 text-xs" :class="activeName === item.name ? 'bg-brand-50 font-medium text-brand-700' : 'text-ink-600'">{{ item.label }}</router-link>
      </nav>

      <main class="flex-1 overflow-y-auto px-4 py-5 sm:px-6 sm:py-6">
        <div class="mx-auto w-full max-w-[1400px]">
          <router-view v-slot="{ Component }">
            <transition name="page-fade" mode="out-in">
              <component :is="Component" :key="route.fullPath" />
            </transition>
          </router-view>
        </div>
      </main>
    </div>

    <button
      type="button"
      class="fixed bottom-7 right-7 z-40 flex h-12 w-12 cursor-pointer items-center justify-center rounded-full bg-brand-600 text-white shadow-lg shadow-brand-600/30 transition hover:scale-105 hover:bg-brand-700"
      title="打开 AI 助手"
      @click="aiVisible = true"
    >
      <Sparkles :size="20" />
    </button>

    <AiDrawer v-model="aiVisible" :ticket-id="aiTicketId" />
    <ProfileDialog v-model="profileVisible" />
  </div>
</template>
