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