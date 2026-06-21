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