import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    // 独立全屏页面，不套 AppLayout：强制改密时用户不应该看到任何业务入口
    path: '/change-password',
    name: 'change-password',
    component: () => import('../views/ChangePasswordView.vue'),
    meta: { title: '修改密码' }
  },
  {
    path: '/',
    component: () => import('../layouts/AppLayout.vue'),
    children: [
      {
        path: '',
        name: 'workbench',
        component: () => import('../views/WorkbenchView.vue'),
        meta: { title: '工作台' }
      },
      {
        path: 'tickets',
        name: 'tickets',
        component: () => import('../views/TicketListView.vue'),
        meta: { title: '工单' }
      },
      {
        path: 'tickets/create',
        name: 'ticket-create',
        component: () => import('../views/TicketCreateView.vue'),
        meta: { title: '新建工单' }
      },
      {
        path: 'tickets/:id',
        name: 'ticket-detail',
        component: () => import('../views/TicketDetailView.vue'),
        meta: { title: '工单详情' }
      },
      {
        path: 'kb',
        name: 'kb',
        component: () => import('../views/KnowledgeView.vue'),
        meta: { title: '知识库' }
      },
      {
        path: 'admin/users',
        name: 'admin-users',
        component: () => import('../views/AdminUsersView.vue'),
        meta: { title: '用户管理', roles: ['ADMIN'] }
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

router.beforeEach(async (to) => {
  if (to.meta.public) {
    return true
  }

  const store = useUserStore()
  if (!store.user) {
    // 刷新页面后 Pinia 是空的，用 /auth/me 从前端恢复会话；401 就回登录页
    const ok = await store.fetchMe()
    if (!ok) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
  }

  // 管理员重置密码后：除改密页外，任何业务页面都先跳回改密页
  if (store.mustChangePassword && to.name !== 'change-password') {
    return { name: 'change-password' }
  }

  if (to.meta.roles && !to.meta.roles.includes(store.role)) {
    // 前端拦截只是体验优化，真正的权限校验始终在后端
    return { name: 'workbench' }
  }
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · ISD 智能服务台` : 'ISD 智能服务台'
})

export default router
