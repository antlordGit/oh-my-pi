import { api } from './http'

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