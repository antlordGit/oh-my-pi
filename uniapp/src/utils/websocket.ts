/**
 * WebSocket 封装 —— 用于 ChatView 实时流式通信
 *
 * 替代 H5/ChatView.vue:307-340 的 new WebSocket + 指数回退重连逻辑。
 * 内部使用 uni.connectSocket，返回 SocketTask，
 * 支持 onFrame / onClose 回调注册。
 */

const BASE_DELAY = 1500
const MAX_RECONNECT = 5
const AUTH_CLOSE_CODES = [1003, 4001]

export type FrameHandler = (frame: Record<string, unknown>) => void
export type CloseHandler = (code: number, reason: string) => void

export class OmpSocket {
  private url: string
  private task: UniApp.SocketTask | null = null
  private frameHandler: FrameHandler | null = null
  private closeHandler: CloseHandler | null = null
  private reconnectAttempts = 0
  private shouldReconnect = true
  private manualClose = false

  constructor(url: string) {
    this.url = url
  }

  connect(): Promise<void> {
    return new Promise((resolve, reject) => {
      if (this.task) {
        resolve()
        return
      }

      const token = uni.getStorageSync('omp.token') || ''
      const fullUrl = this.buildFullUrl()

      this.task = uni.connectSocket({
        url: fullUrl,
        header: {
          Authorization: `Bearer ${token}`,
        },
        success: () => {
          // 连接建立成功，等待 onOpen
        },
        fail: (err) => {
          this.task = null
          reject(new Error(err.errMsg || 'WebSocket 连接失败'))
        },
      })

      if (!this.task) {
        reject(new Error('SocketTask 未返回'))
        return
      }

      this.task.onOpen(() => {
        this.reconnectAttempts = 0
        this.shouldReconnect = true
        resolve()
      })

      this.task.onMessage((res) => {
        try {
          const frame = JSON.parse(res.data as string)
          if (this.frameHandler) this.frameHandler(frame)
        } catch {
          // 非 JSON 帧忽略
        }
      })

      this.task.onClose((res) => {
        const code = res.code
        const reason = res.reason || ''

        // 鉴权失败码不重连，直接跳转登录
        if (AUTH_CLOSE_CODES.includes(code)) {
          this.shouldReconnect = false
          uni.removeStorageSync('omp.token')
          uni.reLaunch({ url: '/pages/login/login' })
        }

        if (this.closeHandler) this.closeHandler(code, reason)

        this.task = null

        // 非手动关闭 & 允许重连 & 未超最大次数
        if (!this.manualClose && this.shouldReconnect && this.reconnectAttempts < MAX_RECONNECT) {
          this.reconnectAttempts++
          const delay = BASE_DELAY * Math.pow(2, this.reconnectAttempts - 1)
          setTimeout(() => {
            this.connect().catch(() => {
              // 静默处理重连失败
            })
          }, delay)
        }
      })

      this.task.onError((err) => {
        // 错误会触发 onClose，这里仅日志
        console.warn('[OmpSocket] error:', err.errMsg)
      })
    })
  }

  private buildFullUrl(): string {
    // 小程序端：协议由运行环境自动选择，开发期直连 IP
    // 生产期需在 manifest.json 配置 socket 合法域名
    const token = uni.getStorageSync('omp.token') || ''
    // 如果 url 已经是完整 URL（以 ws/wss 开头），直接使用
    if (this.url.startsWith('ws')) return this.url
    // 否则拼接 BASE_URL（开发期 IP）
    const base = 'ws://192.168.1.100:8080' // TODO: 与 http.ts BASE_URL 同步
    return `${base}${this.url}`
  }

  send(frame: Record<string, unknown>): Promise<void> {
    return new Promise((resolve, reject) => {
      if (!this.task) {
        reject(new Error('WebSocket 未连接'))
        return
      }
      this.task.send({
        data: JSON.stringify(frame),
        success: () => resolve(),
        fail: (err) => reject(new Error(err.errMsg || '发送失败')),
      })
    })
  }

  close() {
    this.manualClose = true
    if (this.task) {
      this.task.close({
        code: 1000,
        reason: 'user close',
      })
      this.task = null
    }
  }

  onFrame(handler: FrameHandler) {
    this.frameHandler = handler
  }

  onClose(handler: CloseHandler) {
    this.closeHandler = handler
  }
}