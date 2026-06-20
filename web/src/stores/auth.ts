import { defineStore } from 'pinia'
import { api, setToken, getToken } from '@/api/http'
import { ref, computed } from 'vue'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(getToken())
  const username = ref<string | null>(null)
  const role = ref<string | null>(null)
  const identityLevel = ref<string | null>(null)
  const tenantId = ref<number | null>(null)
  // 当前用户的权限码集合（来自 /api/system/me/permissions）。驱动菜单/按钮显示隐藏。
  const permissions = ref<string[]>([])

  const isAdmin = computed(() => role.value === 'admin' || identityLevel.value === 'admin' || identityLevel.value === 'super_admin')
  const isSuperAdmin = computed(() => identityLevel.value === 'super_admin')
  const isAuthed = computed(() => !!token.value)

  /** 判断当前用户是否拥有某个权限码。超级管理员恒为 true。 */
  function hasPerm(code: string): boolean {
    if (isSuperAdmin.value) return true
    return permissions.value.includes(code)
  }

  /** 拉取当前用户权限码（失败时静默置空，不阻塞页面）。 */
  async function loadPermissions() {
    if (!token.value) { permissions.value = []; return }
    try {
      const r = await api.get('/api/system/me/permissions')
      permissions.value = Array.isArray(r.data) ? r.data : []
    } catch {
      permissions.value = []
    }
  }

  async function login(username_: string, password: string) {
    const r = await api.post('/api/auth/login', { username: username_, password })
    setToken(r.data.token)
    token.value = r.data.token
    username.value = r.data.username
    role.value = r.data.role
    identityLevel.value = r.data.identityLevel
    tenantId.value = r.data.tenantId
    await loadPermissions()
    // 登录后加载菜单
    const { useMenuStore } = await import('./menu')
    await useMenuStore().loadMenus()
  }

  async function bootstrap() {
    if (!token.value) return
    try {
      const r = await api.get('/api/auth/me')
      username.value = r.data.username
      role.value = r.data.role
      identityLevel.value = r.data.identityLevel
      tenantId.value = r.data.tenantId
      await loadPermissions()
      // 加载菜单
      const { useMenuStore } = await import('./menu')
      await useMenuStore().loadMenus()
    } catch {
      logout()
    }
  }

  function logout() {
    setToken(null)
    token.value = null
    username.value = null
    role.value = null
    identityLevel.value = null
    tenantId.value = null
    permissions.value = []
    // 清空菜单
    import('./menu').then(({ useMenuStore }) => useMenuStore().clearMenus())
  }

  return { token, username, role, identityLevel, tenantId, permissions, isAdmin, isSuperAdmin, isAuthed, hasPerm, loadPermissions, login, bootstrap, logout }
})