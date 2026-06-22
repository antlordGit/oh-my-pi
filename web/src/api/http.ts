import axios, { type AxiosInstance } from 'axios'

const TOKEN_KEY = 'omp.token'

export const api: AxiosInstance = axios.create({
  baseURL: '/',
  timeout: 30_000,
})

api.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

api.interceptors.response.use(
  (r) => r,
  (err) => {
    if (err.response?.status === 401) {
      localStorage.removeItem(TOKEN_KEY)
      window.location.href = '/login'
    }
    // 统一规范化错误数据：把后端可能用的 message/msg/error 字段统一搬到 data.error，
    // 这样所有调用方 `e.response.data.error || '...失败'` 都能拿到正确文案。
    // 兼容：维护拦截器、Spring 默认错误格式、业务异常。
    const data = err.response?.data
    if (data && typeof data === 'object' && !data.error) {
      const fallback = data.message || data.msg || data.errorMessage
      if (fallback) data.error = fallback
    }
    return Promise.reject(err)
  },
)

export function setToken(t: string | null) {
  if (t) localStorage.setItem(TOKEN_KEY, t)
  else localStorage.removeItem(TOKEN_KEY)
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}