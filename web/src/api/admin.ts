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