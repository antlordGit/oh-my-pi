import { api } from '@/api/http'

export interface Repo {
  id: number
  repoId: string
  displayName: string
  createdAt?: string
}

export async function listRepos(): Promise<Repo[]> {
  const r = await api.get('/api/repos')
  return r.data
}

export async function createRepo(repoId: string, displayName?: string): Promise<Repo> {
  const r = await api.post('/api/repos', { repoId, displayName })
  return r.data
}

/** 导入工程：上传本地文件夹，文件夹名作为仓库标识 */
export async function importRepo(repoId: string, files: File[]): Promise<Repo> {
  const fd = new FormData()
  fd.append('repoId', repoId)
  for (const f of files) fd.append('files', f)
  const r = await api.post('/api/repos/import', fd, {
    timeout: 120_000,
  })
  return r.data
}

/**
 * 克隆仓库：把远程 git 仓库拉取到用户工作区。
 * 支持用户名/密码认证（用于内网 GitLab 等需要认证的仓库）。
 * 服务端会做 scheme 白名单 + SSRF 校验，支持 http/https/git 的公网/内网仓库。
 */
export async function cloneRepo(
  repoId: string,
  url: string,
  branch?: string,
  depth?: number,
  username?: string,
  password?: string,
): Promise<Repo> {
  const r = await api.post('/api/repos/clone', { repoId, url, branch, depth, username, password }, {
    timeout: 180_000,
  })
  return r.data
}

/**
 * 初始化模板：把内置的前端 / 后端模板拷贝到工作区，然后 git init。
 * 模板是 classpath 资源，体积 < 50KB，默认 timeout 即可。
 */
export async function initRepo(
  repoId: string,
  template: 'frontend' | 'backend',
  displayName?: string,
): Promise<Repo> {
  const r = await api.post('/api/repos/init', { repoId, template, displayName })
  return r.data
}

export async function copyRepo(sourceRepoId: string, targetRepoId: string, displayName?: string): Promise<Repo> {
  const r = await api.post(`/api/repos/${sourceRepoId}/copy`, { targetRepoId, displayName })
  return r.data
}

export async function deleteRepo(repoId: string): Promise<{ ok: boolean; repoId: string }> {
  const r = await api.delete(`/api/repos/${repoId}`)
  return r.data
}

/** 导出仓库为 zip 文件，触发浏览器下载 */
export async function exportRepo(repoId: string, displayName: string): Promise<void> {
  const r = await api.get(`/api/repos/${repoId}/export`, {
    responseType: 'blob',
    timeout: 120_000,
  })
  const url = URL.createObjectURL(r.data)
  const a = document.createElement('a')
  a.href = url
  a.download = `${displayName || repoId}.zip`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

export async function listFiles(repoId: string): Promise<string[]> {
  const r = await api.get(`/api/repos/${repoId}/files`)
  return r.data
}

export async function readFile(repoId: string, path: string): Promise<{path: string; content: string}> {
  const r = await api.get(`/api/repos/${repoId}/file`, { params: { path } })
  return r.data
}

export async function writeFile(repoId: string, path: string, content: string): Promise<{ok: boolean; path: string}> {
  const r = await api.put(`/api/repos/${repoId}/file`, { content }, { params: { path } })
  return r.data
}

export async function log(repoId: string, n = 20): Promise<string> {
  const r = await api.get(`/api/repos/${repoId}/log`, { params: { n } })
  return r.data.log
}