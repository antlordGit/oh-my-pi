import { api } from './http'

// ==================== 用户管理 ====================

export interface UserInfo {
  id: number
  username: string
  name: string | null
  role: string
  identityLevel: string
  tenantId: number | null
  tenantName: string | null
  roleNames: string[]
  enabled: boolean
  createdAt: string | null
  lastLoginAt: string | null
  roleIds?: number[]
}

export interface CreateUserParams {
  username: string
  name?: string
  password: string
  identityLevel?: string
  tenantId?: number
  roleIds?: number[]
}

export interface UpdateUserParams {
  name?: string
  identityLevel?: string
  tenantId?: number
  enabled?: boolean
}

export async function listUsers(): Promise<UserInfo[]> {
  const r = await api.get('/api/system/users')
  return r.data
}

export async function getUser(id: number): Promise<UserInfo> {
  const r = await api.get(`/api/system/users/${id}`)
  return r.data
}

export async function createUser(params: CreateUserParams): Promise<UserInfo> {
  const r = await api.post('/api/system/users', params)
  return r.data
}

export async function updateUser(id: number, params: UpdateUserParams): Promise<UserInfo> {
  const r = await api.put(`/api/system/users/${id}`, params)
  return r.data
}

export async function assignUserRoles(id: number, roleIds: number[]): Promise<UserInfo> {
  const r = await api.put(`/api/system/users/${id}/roles`, { roleIds })
  return r.data
}

export async function deleteUser(id: number): Promise<void> {
  await api.delete(`/api/system/users/${id}`)
}

// ==================== 租户管理 ====================

export interface TenantInfo {
  id: number
  tenantCode: string
  tenantName: string
  description: string | null
  enabled: boolean
  createdAt: string | null
}

export interface CreateTenantParams {
  tenantCode: string
  tenantName?: string
  description?: string
}

export interface UpdateTenantParams {
  tenantName?: string
  description?: string
  enabled?: boolean
}

export async function listTenants(): Promise<TenantInfo[]> {
  const r = await api.get('/api/system/tenants')
  return r.data
}

export async function createTenant(params: CreateTenantParams): Promise<TenantInfo> {
  const r = await api.post('/api/system/tenants', params)
  return r.data
}

export async function updateTenant(id: number, params: UpdateTenantParams): Promise<TenantInfo> {
  const r = await api.put(`/api/system/tenants/${id}`, params)
  return r.data
}

export async function deleteTenant(id: number): Promise<void> {
  await api.delete(`/api/system/tenants/${id}`)
}

// ==================== 角色管理 ====================

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

export interface CreateRoleParams {
  roleCode: string
  roleName: string
  description?: string
  tenantId?: number
}

export interface UpdateRoleParams {
  roleName?: string
  description?: string
  enabled?: boolean
}

export async function listRoles(): Promise<RoleInfo[]> {
  const r = await api.get('/api/system/roles')
  return r.data
}

export async function getRole(id: number): Promise<RoleInfo> {
  const r = await api.get(`/api/system/roles/${id}`)
  return r.data
}

export async function createRole(params: CreateRoleParams): Promise<RoleInfo> {
  const r = await api.post('/api/system/roles', params)
  return r.data
}

export async function updateRole(id: number, params: UpdateRoleParams): Promise<RoleInfo> {
  const r = await api.put(`/api/system/roles/${id}`, params)
  return r.data
}

export async function assignRoleMenus(id: number, menuIds: number[]): Promise<RoleInfo> {
  const r = await api.put(`/api/system/roles/${id}/menus`, { menuIds })
  return r.data
}

export async function deleteRole(id: number): Promise<void> {
  await api.delete(`/api/system/roles/${id}`)
}

// ==================== 菜单管理 ====================

export interface MenuInfo {
  id: number
  parentId: number | null
  menuCode: string
  menuName: string
  menuType: string
  path: string | null
  component: string | null
  icon: string | null
  sortOrder: number
  permission: string | null
  enabled: boolean
  createdAt: string | null
  children?: MenuInfo[]
}

export interface CreateMenuParams {
  menuCode: string
  menuName: string
  menuType?: string
  parentId?: number
  path?: string
  component?: string
  icon?: string
  sortOrder?: number
  permission?: string
}

export interface UpdateMenuParams {
  menuName?: string
  menuType?: string
  parentId?: number
  path?: string
  component?: string
  icon?: string
  sortOrder?: number
  permission?: string
  enabled?: boolean
}

export async function listMenus(tree?: boolean): Promise<MenuInfo[]> {
  const r = await api.get('/api/system/menus', { params: { tree: tree ?? true } })
  return r.data
}

export async function createMenu(params: CreateMenuParams): Promise<MenuInfo> {
  const r = await api.post('/api/system/menus', params)
  return r.data
}

export async function updateMenu(id: number, params: UpdateMenuParams): Promise<MenuInfo> {
  const r = await api.put(`/api/system/menus/${id}`, params)
  return r.data
}

export async function deleteMenu(id: number): Promise<void> {
  await api.delete(`/api/system/menus/${id}`)
}

// ==================== 模型配置管理 ====================

export interface ModelConfigInfo {
  id: number
  configName: string
  displayName: string | null
  provider: string
  modelId: string
  baseUrl: string | null
  api: string | null
  apiKey: string | null
  configJson: string | null
  remark: string | null
  active: boolean
  sortOrder: number
  createdAt: string | null
  updatedAt: string | null
}

export interface CreateModelConfigParams {
  configName: string
  displayName?: string
  provider: string
  modelId: string
  baseUrl?: string
  api?: string
  apiKey?: string
  configJson?: string
  remark?: string
}

export interface UpdateModelConfigParams {
  configName?: string
  displayName?: string
  provider?: string
  modelId?: string
  baseUrl?: string
  api?: string
  apiKey?: string
  configJson?: string
  remark?: string
}

export async function listModelConfigs(): Promise<ModelConfigInfo[]> {
  const r = await api.get('/api/system/model-configs')
  return r.data
}

export async function getModelConfig(id: number): Promise<ModelConfigInfo> {
  const r = await api.get(`/api/system/model-configs/${id}`)
  return r.data
}

export async function createModelConfig(params: CreateModelConfigParams): Promise<ModelConfigInfo> {
  const r = await api.post('/api/system/model-configs', params)
  return r.data
}

export async function updateModelConfig(id: number, params: UpdateModelConfigParams): Promise<ModelConfigInfo> {
  const r = await api.put(`/api/system/model-configs/${id}`, params)
  return r.data
}

export async function activateModelConfig(id: number): Promise<ModelConfigInfo> {
  const r = await api.put(`/api/system/model-configs/${id}/activate`)
  return r.data
}

export async function deleteModelConfig(id: number): Promise<void> {
  await api.delete(`/api/system/model-configs/${id}`)
}

// ==================== 当前用户权限 ====================

export interface MyInfo {
  id: number
  username: string
  identityLevel: string
  tenantId: number | null
  superAdmin: boolean
  roles: RoleInfo[]
}

export async function getMyInfo(): Promise<MyInfo> {
  const r = await api.get('/api/system/me')
  return r.data
}

export async function getMyMenus(): Promise<MenuInfo[]> {
  const r = await api.get('/api/system/me/menus')
  return r.data
}
