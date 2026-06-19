import { defineStore } from 'pinia'
import { api, setToken, getToken } from '@/api/http'
import { ref, computed } from 'vue'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(getToken())
  const username = ref<string | null>(null)
  const role = ref<string | null>(null)

  const isAdmin = computed(() => role.value === 'admin')
  const isAuthed = computed(() => !!token.value)

  async function login(username_: string, password: string) {
    const r = await api.post('/api/auth/login', { username: username_, password })
    setToken(r.data.token)
    token.value = r.data.token
    username.value = r.data.username
    role.value = r.data.role
  }

  async function bootstrap() {
    if (!token.value) return
    try {
      const r = await api.get('/api/auth/me')
      username.value = r.data.username
      role.value = r.data.role
    } catch {
      logout()
    }
  }

  function logout() {
    setToken(null)
    token.value = null
    username.value = null
    role.value = null
  }

  return { token, username, role, isAdmin, isAuthed, login, bootstrap, logout }
})