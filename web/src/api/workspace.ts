import { api } from '@/api/http'

/**
 * Workspace tree node as returned by `GET /api/repos/{repoId}/tree`.
 * `path` is relative to the workspace root. For directories `children`
 * contains the immediate children — the UI re-requests deeper levels
 * by passing a higher `depth`.
 */
export interface WorkspaceNode {
  name: string
  path: string
  type: 'dir' | 'file'
  children?: WorkspaceNode[]
}

export async function getWorkspaceTree(
  repoId: string,
  depth = 2,
): Promise<WorkspaceNode[]> {
  const r = await api.get(`/api/repos/${repoId}/tree`, { params: { depth } })
  return r.data
}