/**
 * uni.request 适配器 —— 接口签名与 axios 兼容
 *
 * 暴露 api.get / post / put / delete 四个方法，
 * 返回 Promise<{data, status, headers}>，
 * 内部注入 Authorization 头、处理 401 跳转登录、
 * 统一规范化 error 字段。
 */

const TOKEN_KEY = 'omp.token'

// 开发期后端地址
//
// H5 端（浏览器）：留空走相对路径，由 manifest.json 中的 vite devServer.proxy
// 代理 /api 到 http://localhost:8080。如需访问其它机器，改成 IP 即可。
//
// 小程序 / App 端：必须填完整 IP（同时关闭 manifest.json 的 urlCheck）。
// 通过 uni-app 条件编译保证两端使用各自的地址。

// #ifdef H5
const BASE_URL = ''
// #endif

// #ifndef H5
// const BASE_URL = 'http://192.168.1.100:8080'
// #endif

type Method = 'GET' | 'POST' | 'PUT' | 'DELETE'

interface RequestOptions {
  params?: Record<string, unknown>
  data?: unknown
  timeout?: number
  headers?: Record<string, string>
  responseType?: 'text' | 'arraybuffer'
}

interface Response<T = unknown> {
  data: T
  status: number
  headers: Record<string, string>
}

class HttpError extends Error {
  status: number
  data: unknown
  constructor(message: string, status: number, data: unknown) {
    super(message)
    this.name = 'HttpError'
    this.status = status
    this.data = data
  }
}

function getToken(): string | null {
  try {
    return uni.getStorageSync(TOKEN_KEY) || null
  } catch {
    return null
  }
}

function setToken(t: string | null) {
  try {
    if (t) uni.setStorageSync(TOKEN_KEY, t)
    else uni.removeStorageSync(TOKEN_KEY)
  } catch {
    // 静默处理
  }
}

function normalizeError(data: Record<string, unknown> | null): Record<string, unknown> | null {
  if (!data || typeof data !== 'object') return data
  // 后端可能用 message / msg / errorMessage / error 四种字段返回错误文案
  if (!data.error) {
    const fallback = (data as Record<string, unknown>).message ||
      (data as Record<string, unknown>).msg ||
      (data as Record<string, unknown>).errorMessage
    if (fallback) data.error = fallback
  }
  return data
}

function request<T = unknown>(
  method: Method,
  url: string,
  options: RequestOptions = {},
): Promise<Response<T>> {
  return new Promise((resolve, reject) => {
    const token = getToken()
    const header: Record<string, string> = {
      'Content-Type': 'application/json',
      ...options.headers,
    }
    if (token) header['Authorization'] = `Bearer ${token}`

    const fullUrl = url.startsWith('http') ? url : BASE_URL + url

    uni.request({
      url: fullUrl,
      method,
      data: options.data,
      timeout: options.timeout || 30000,
      header,
      responseType: options.responseType || 'text',
      success: (res) => {
        const status = res.statusCode
        const data = res.data as T
        const headers = (res.header || {}) as Record<string, string>

        if (status === 401) {
          setToken(null)
          uni.reLaunch({ url: '/pages/login/login' })
          const err = new HttpError('未授权，请重新登录', status, data)
          reject(err)
          return
        }

        if (status >= 400) {
          const errData = normalizeError(data as Record<string, unknown>)
          const msg = (errData?.error as string) || `请求失败 (${status})`
          const err = new HttpError(msg, status, errData)
          reject(err)
          return
        }

        resolve({ data, status, headers })
      },
      fail: (err) => {
        // 网络错误
        const msg = err.errMsg || '网络连接失败'
        reject(new HttpError(msg, 0, null))
      },
    })
  })
}

export const api = {
  get<T = unknown>(url: string, options?: RequestOptions): Promise<Response<T>> {
    return request<T>('GET', url, options)
  },
  post<T = unknown>(url: string, data?: unknown, options?: RequestOptions): Promise<Response<T>> {
    return request<T>('POST', url, { ...options, data })
  },
  put<T = unknown>(url: string, data?: unknown, options?: RequestOptions): Promise<Response<T>> {
    return request<T>('PUT', url, { ...options, data })
  },
  delete<T = unknown>(url: string, options?: RequestOptions): Promise<Response<T>> {
    return request<T>('DELETE', url, options)
  },
}

export { setToken, getToken, HttpError }