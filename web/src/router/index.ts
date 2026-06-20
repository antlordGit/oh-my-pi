import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useMenuStore } from '@/stores/menu'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('@/views/LoginView.vue') },
    // 聊天页全屏沉浸式，不套用 AppLayout 的侧边栏+顶栏
    { path: '/sessions/:id', component: () => import('@/views/ChatView.vue'), meta: { requiresAuth: true } },
    {
      path: '/',
      component: () => import('@/components/AppLayout.vue'),
      meta: { requiresAuth: true },
      children: [
        { path: '', redirect: '/sessions' },
        { path: 'sessions', component: () => import('@/views/SessionListView.vue') },
        { path: 'admin', component: () => import('@/views/admin/AdminView.vue') },
        { path: 'system', component: () => import('@/views/system/SystemView.vue') },
      ],
    },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  // 如果有 token 但 store 还未 bootstrap（刷新页面直达内部路由），先拉取身份、权限和菜单
  if (auth.token && !auth.identityLevel) {
    await auth.bootstrap()
  }
  if (to.meta.requiresAuth && !auth.token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  // 基于菜单权限的访问控制：超级管理员放行；其余用户校验目标 path 是否在其菜单内。
  // /sessions 始终放行（工作台兜底），菜单未加载时也放行，避免误拦截。
  if (auth.token && to.meta.requiresAuth && !auth.isSuperAdmin) {
    const menuStore = useMenuStore()
    const base = to.path
    const isWorkspace = base === '/sessions' || base.startsWith('/sessions/')
    if (!isWorkspace && menuStore.menus.length && !menuStore.canAccess(base)) {
      return { path: '/sessions' }
    }
  }
})
