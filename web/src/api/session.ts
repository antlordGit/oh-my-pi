import { api } from '@/api/http'

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

/** 打开 IDE，返回 code-server/openvscode-server 的直连 URL */
export async function openSessionIde(sessionId: string): Promise<{ url: string }> {
  const r = await api.post(`/api/sessions/${sessionId}/ide/open`)
  return r.data
}

export async function getState(sessionId: string): Promise<any> {
  const r = await api.get(`/api/sessions/${sessionId}/state`)
  return r.data
}

export async function getMessages(sessionId: string): Promise<any> {
  const r = await api.get(`/api/sessions/${sessionId}/messages`)
  return r.data
}

/** 与底层 Pi 协议 ImageContent 对齐：base64 编码图片 + MIME */
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

export async function newSession(sessionId: string, parentSession?: string): Promise<any> {
  const r = await api.post(`/api/sessions/${sessionId}/new-session`, { parentSession })
  return r.data
}

export async function branch(sessionId: string, entryId: string): Promise<any> {
  const r = await api.post(`/api/sessions/${sessionId}/branch`, { entryId })
  return r.data
}

export function wsUrl(sessionId: string): string {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
  const token = localStorage.getItem('omp.token') || ''
  return `${proto}//${location.host}/ws/sessions/${sessionId}?token=${encodeURIComponent(token)}`
}