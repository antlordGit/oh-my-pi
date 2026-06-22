import { api } from './http'

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export async function listSessionsPaged(page: number, size: number): Promise<PageResult<any>> {
  const r = await api.get('/admin/sessions', { params: { page, size } })
  return r.data
}

export async function listAuditPaged(page: number, size: number, sessionId?: string): Promise<PageResult<any>> {
  const r = await api.get('/admin/audit', { params: { page, size, sessionId } })
  return r.data
}

// ============================================================================
// 系统维护
// ============================================================================

export interface MaintenanceStatus {
  enabled: boolean
  createdAt?: string
  expiresAt?: string
  enabledBy?: string
}

export interface StreamingSession {
  sessionId: string
  title?: string
  userId?: number
  streamingSince: string
  streamingSeconds: number
}

export interface StreamingSessionsResp {
  items: StreamingSession[]
  total: number
  canStop: boolean
}

/** 查询当前维护状态 */
export async function getMaintenanceStatus(): Promise<MaintenanceStatus> {
  try {
    const r = await api.get('/admin/maintenance/status')
    return r.data || { enabled: false }
  } catch (e: any) {
    // 后端还没部署新接口时返回默认值，避免白屏
    if (e.response?.status === 404) return { enabled: false }
    throw e
  }
}

/** 开启维护模式（7天 TTL） */
export async function enableMaintenance(): Promise<{ ok: boolean; status: MaintenanceStatus }> {
  const r = await api.post('/admin/maintenance/enable')
  return r.data || { ok: true, status: { enabled: true } }
}

/** 解除维护模式 */
export async function disableMaintenance(): Promise<{ ok: boolean }> {
  const r = await api.post('/admin/maintenance/disable')
  return r.data || { ok: true }
}

/** 查询当前正在推流的会话列表 */
export async function getStreamingSessions(): Promise<StreamingSessionsResp> {
  try {
    const r = await api.get('/admin/maintenance/streaming-sessions')
    return r.data || { items: [], total: 0, canStop: true }
  } catch (e: any) {
    if (e.response?.status === 404) return { items: [], total: 0, canStop: false }
    throw e
  }
}