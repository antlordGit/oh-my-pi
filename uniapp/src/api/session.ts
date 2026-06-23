import { api } from './http'

// ==================== 会话接口 ====================

export interface SessionSummary {
  sessionId: string
  repoId: string
  title: string
  status: string
  ompSessionFile: string
  createdAt?: string
  lastActiveAt?: string
}

export async function listSessions(repoId?: string): Promise<SessionSummary[]> {
  const r = await api.get('/api/sessions', { params: repoId ? { repoId } : {} })
  return r.data
}

export async function createSession(repoId: string, title?: string): Promise<SessionSummary> {
  const r = await api.post('/api/sessions', { repoId, title })
  return r.data
}

export async function getSession(sessionId: string): Promise<SessionSummary> {
  const r = await api.get(`/api/sessions/${sessionId}`)
  return r.data
}

export async function getMessages(sessionId: string): Promise<any> {
  const r = await api.get(`/api/sessions/${sessionId}/messages`)
  return r.data
}

export interface ImageContent {
  data: string
  mimeType: string
}

export async function prompt(
  sessionId: string,
  message: string,
  images?: ImageContent[],
  streamingBehavior?: string,
): Promise<void> {
  const body: Record<string, unknown> = { message }
  if (images && images.length > 0) body.images = images
  if (streamingBehavior) body.streamingBehavior = streamingBehavior
  await api.post(`/api/sessions/${sessionId}/prompt`, body)
}

export async function abort(sessionId: string): Promise<void> {
  await api.post(`/api/sessions/${sessionId}/abort`)
}

export async function archive(sessionId: string): Promise<void> {
  await api.post(`/api/sessions/${sessionId}/archive`)
}

export async function unarchive(sessionId: string): Promise<void> {
  await api.post(`/api/sessions/${sessionId}/unarchive`)
}

export async function deleteArchivedSession(sessionId: string): Promise<void> {
  await api.post(`/admin/sessions/${sessionId}/delete`)
}

export async function newSession(sessionId: string, parentSession?: string): Promise<any> {
  const r = await api.post(`/api/sessions/${sessionId}/new-session`, { parentSession })
  return r.data
}

export async function branch(sessionId: string, entryId: string): Promise<any> {
  const r = await api.post(`/api/sessions/${sessionId}/branch`, { entryId })
  return r.data
}

/** 构建 WebSocket 连接 URL（小程序端使用） */
export function wsUrl(sessionId: string): string {
  const token = uni.getStorageSync('omp.token') || ''
  // uni-app 中自动根据编译平台选择 ws/wss 协议
  // 开发期通过 manifest.json 的 urlCheck:false + 直接写 IP 连接
  return `/ws/sessions/${sessionId}?token=${encodeURIComponent(token)}`
}