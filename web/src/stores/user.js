import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi } from '../api/auth'

/**
 * 会话状态只保存"当前用户是谁"。
 * 不做 token 托管：凭证是 HttpOnly Cookie，JS 读不到也不该读。
 */
export const useUserStore = defineStore('user', () => {
  const user = ref(null)

  const role = computed(() => user.value?.role || '')
  const userId = computed(() => user.value?.id || null)
  const displayName = computed(() => user.value?.fullName || user.value?.username || '')
  const isAdmin = computed(() => role.value === 'ADMIN')
  const isSupport = computed(() => role.value === 'SUPPORT')
  /** 技术支持或管理员：可以查看全部工单、维护知识库 */
  const isStaff = computed(() => role.value === 'SUPPORT' || role.value === 'ADMIN')
  /** 管理员重置后的临时密码：登录后必须先去改密页 */
  const mustChangePassword = computed(() => user.value?.mustChangePassword === true)
  const phone = computed(() => user.value?.phone || '')
  const email = computed(() => user.value?.email || '')

  async function fetchMe() {
    try {
      user.value = await authApi.me()
      return true
    } catch (error) {
      user.value = null
      return false
    }
  }

  async function login(form) {
    const data = await authApi.login(form)
    user.value = data
    return data
  }

  async function logout() {
    try {
      await authApi.logout()
    } finally {
      user.value = null
    }
  }

  /** 个人资料保存、改密成功等场景：用后端返回值整体替换，避免逐字段同步遗漏 */
  function setUser(data) {
    user.value = data
  }

  return {
    user,
    role,
    userId,
    displayName,
    isAdmin,
    isSupport,
    isStaff,
    mustChangePassword,
    phone,
    email,
    fetchMe,
    login,
    logout,
    setUser
  }
})
