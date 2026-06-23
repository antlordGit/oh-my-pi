import { api } from './http'

// ==================== 当前用户 ====================

export interface RoleInfo {
  id: number
  tenantId: number | null
  roleCode: string
  roleName: string
  description: string | null
  enabled: boolean
  createdAt: string | null
  menuIds?: number[]
}

export interface MyInfo {
  id: number
  username: string
  identityLevel: string
  tenantId: number | null
  superAdmin: boolean
  roles: RoleInfo[]
}

// 仅保留 /api/system/me 接口供登入后的身份确认
// 其他菜单/权限接口（getMyMenus / getMyInfo）不在此项目中调用

export async function getMyInfo(): Promise<MyInfo> {
  const r = await api.get('/api/system/me')
  return r.data
}