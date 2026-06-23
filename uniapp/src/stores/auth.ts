import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { api, setToken, getToken } from '@/api/http'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(getToken())
  const username = ref<string | null>(null)
  const identityLevel = ref<string | null>(null)
  const tenantId = ref<number | null>(null)

  const isAdmin = computed(
    () => role.value === 'admin' ||
      identityLevel.value === 'admin' ||
      identityLevel.value === 'super_admin',
  )
  const isSuperAdmin = computed(() => identityLevel.value === 'super_admin')
  const isAuthed = computed(() => !!token.value)

  // 小程序端无角色与菜单权限模型，role 仅用于本地判断
  const role = ref<string | null>(null)

  async function login(username_: string, password: string) {
    const r = await api.post('/api/auth/login', { username: username_, password })
    setToken(r.data.token)
    token.value = r.data.token
    username.value = r.data.username
    role.value = r.data.role
    identityLevel.value = r.data.identityLevel
    tenantId.value = r.data.tenantId
  }

  async function bootstrap() {
    if (!token.value) return
    try {
      const r = await api.get('/api/auth/me')
      username.value = r.data.username
      role.value = r.data.role
      identityLevel.value = r.data.identityLevel
      tenantId.value = r.data.tenantId
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
  }

  return {
    token,
    username,
    role,
    identityLevel,
    tenantId,
    isAdmin,
    isSuperAdmin,
    isAuthed,
    login,
    bootstrap,
    logout,
  }
})