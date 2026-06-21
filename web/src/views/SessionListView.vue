<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useMessage, useDialog } from 'naive-ui'
import { listSessions, createSession, archive, unarchive, type SessionSummary } from '@/api/session'
import { listRepos, createRepo, importRepo, copyRepo, deleteRepo, exportRepo, type Repo } from '@/api/repo'

const router = useRouter()
const auth = useAuthStore()
const msg = useMessage()
const dialog = useDialog()

const sessions = ref<SessionSummary[]>([])
const repos = ref<Repo[]>([])
const loading = ref(false)
const filter = ref<'all' | 'active' | 'archived'>('all')

const newRepoId = ref('')
const newRepoName = ref('')
const newSessionRepo = ref<string>('')
const newSessionTitle = ref('')
const showCreator = ref(false)
const showRepoManager = ref(false)

// 导入工程状态
const importLoading = ref(false)
const importModalVisible = ref(false)
const importFileInput = ref<HTMLInputElement | null>(null)

/** 仓库标识验证规则：英文、数字、-，最长 32 位 */
const REPO_ID_RE = /^[A-Za-z0-9-]{1,32}$/

function validateRepoId(id: string): string | null {
  if (!id) return '仓库标识不能为空'
  if (id.length > 32) return '仓库标识不能超过 32 位'
  if (!REPO_ID_RE.test(id)) return '仓库标识只能包含英文、数字和-'
  return null
}

/** 从文件夹名生成合法的仓库标识：过滤非法字符，最多 32 位 */
function sanitizeRepoId(folderName: string): string {
  // 先尝试直接保留英文/数字/-，移除其他
  let id = folderName
    .replace(/\s+/g, '-')
    .replace(/[^A-Za-z0-9-]/g, '')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '')

  if (id.length > 32) id = id.slice(0, 32)

  // 如果过滤后为空（纯中文等），用前缀+时间戳
  if (!id) {
    id = 'repo-' + Date.now().toString(36)
  }
  return id
}

/** 处理文件夹选择和导入 */
async function onImportFiles(e: Event) {
  const input = e.target as HTMLInputElement
  const files = input.files
  if (!files || !files.length) return

  // 从第一个文件的相对路径中提取文件夹名
  const firstFile = files[0] as any as { webkitRelativePath?: string }
  const relPath = firstFile.webkitRelativePath || files[0].name
  const folderName = relPath.split('/')[0]
  const repoId = sanitizeRepoId(folderName)

  // 验证仓库标识
  const err = validateRepoId(repoId)
  if (err) {
    msg.error(`文件夹名「${folderName}」生成的标识无效：${err}`)
    input.value = ''
    return
  }

  importLoading.value = true
  try {
    await importRepo(repoId, Array.from(files))
    msg.success(`工程已导入（仓库标识: ${repoId}）`)
    input.value = ''
    importModalVisible.value = false
    await refresh()
  } catch (e: any) {
    msg.error(e?.response?.data?.error || '导入失败')
  } finally {
    importLoading.value = false
  }
}

// Copy repo modal state
const copySourceRepo = ref<string>('')
const copyTargetRepoId = ref('')
const copyDisplayName = ref('')
const showCopyModal = ref(false)

const filteredSessions = computed(() =>
  filter.value === 'all' ? sessions.value : sessions.value.filter(s => s.status === filter.value),
)

const activeCount = computed(() => sessions.value.filter(s => effectiveStatus(s) === 'active').length)
const archivedCount = computed(() => sessions.value.filter(s => effectiveStatus(s) === 'archived').length)
const totalSessions = computed(() => sessions.value.length)

async function refresh() {
  loading.value = true
  try {
    const list = await listSessions()
    // Newest first: most recently active sessions float to the top.
    list.sort((a, b) => {
      const ta = a.lastActiveAt ? Date.parse(a.lastActiveAt) : 0
      const tb = b.lastActiveAt ? Date.parse(b.lastActiveAt) : 0
      return tb - ta
    })
    sessions.value = list
    repos.value = await listRepos()
    if (!newSessionRepo.value && repos.value[0]) newSessionRepo.value = repos.value[0].repoId
  } finally { loading.value = false }
}

async function onCreateRepo() {
  if (!newRepoId.value) return
  try {
    await createRepo(newRepoId.value, newRepoName.value || newRepoId.value)
    newRepoId.value = ''; newRepoName.value = ''
    msg.success('仓库已创建')
    await refresh()
  } catch (e: any) { msg.error(e?.response?.data?.error || '创建失败') }
}

async function onCreateSession() {
  if (!newSessionRepo.value) return msg.warning('请选择仓库')
  try {
    const s = await createSession(newSessionRepo.value, newSessionTitle.value || '未命名会话')
    msg.success('会话已开启')
    newSessionTitle.value = ''; showCreator.value = false
    router.push(`/sessions/${s.sessionId}`)
  } catch (e: any) { msg.error(e?.response?.data?.error || '创建失败') }
}

// Repo copy/delete handlers
function openCopyModal(repoId: string) {
  dialog.warning({
    title: '确认复制',
    content: `确定要复制仓库「${repoId}」为新的仓库吗？`,
    positiveText: '继续',
    negativeText: '取消',
    onPositiveClick: () => {
      copySourceRepo.value = repoId
      copyTargetRepoId.value = repoId + '-copy'
      copyDisplayName.value = ''
      showCopyModal.value = true
    },
  })
}

async function doCopyRepo() {
  if (!copyTargetRepoId.value) return msg.warning('请输入目标仓库标识')
  try {
    await copyRepo(copySourceRepo.value, copyTargetRepoId.value, copyDisplayName.value || copyTargetRepoId.value)
    msg.success('仓库已复制')
    showCopyModal.value = false
    await refresh()
  } catch (e: any) { msg.error(e?.response?.data?.error || '复制失败') }
}

function confirmDeleteRepo(repoId: string, displayName: string) {
  dialog.warning({
    title: '确认删除',
    content: `确定删除仓库「${displayName}」及其所有文件？此操作不可恢复。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteRepo(repoId)
        msg.success('仓库已删除')
        if (newSessionRepo.value === repoId) newSessionRepo.value = repos.value[0]?.repoId || ''
        await refresh()
      } catch (e: any) { msg.error(e?.response?.data?.error || '删除失败') }
    }
  })
}

async function onExportRepo(repoId: string, displayName: string) {
  try {
    msg.loading('正在导出…')
    await exportRepo(repoId, displayName)
    msg.success('导出完成')
  } catch (e: any) { msg.error(e?.response?.data?.error || '导出失败') }
}

const OVERRIDES_KEY = 'omp.admin.sessionOverrides'
function setOverride(id: string, status: 'active' | 'archived') {
  try {
    const m = JSON.parse(sessionStorage.getItem(OVERRIDES_KEY) || '{}') as Record<string, string>
    m[id] = status
    sessionStorage.setItem(OVERRIDES_KEY, JSON.stringify(m))
  } catch {}
}

async function onArchive(id: string) {
  try {
    await archive(id)
    // Keep the shared override in step so the row doesn't snap back to its
    // stale value (the admin control room writes the same key).
    setOverride(id, 'archived')
    msg.success('已归档'); await refresh()
  }
  catch (e: any) { msg.error(e?.response?.data?.error || '归档失败') }
}

async function onUnarchive(id: string) {
  try {
    await unarchive(id)
    setOverride(id, 'active')
    msg.success('已恢复'); await refresh()
  }
  catch (e: any) { msg.error(e?.response?.data?.error || '恢复失败') }
}

function effectiveStatus(s: SessionSummary): 'active' | 'archived' {
  // Prefer admin-side optimistic override (persisted in sessionStorage) --
  // keyed by full UUID; also match on the first 8 chars of the sessionId
  // so that both views can find the same override entry.
  const key = OVERRIDES_KEY
  try {
    const overrides = JSON.parse(sessionStorage.getItem(key) || '{}') as Record<string, string>
    const entry = overrides[s.sessionId]
    if (entry) return entry as 'active' | 'archived'
  } catch {}
  return s.status === 'active' ? 'active' : 'archived'
}

function fmtDate(s?: string) {
  if (!s) return '—'
  const d = new Date(s)
  return `${d.getFullYear()}.${String(d.getMonth() + 1).padStart(2, '0')}.${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

onMounted(refresh)
</script>

<template>
  <div class="page">
    <!-- Hero stats — terminal instrumentation -->
    <section class="hero-stats-section fade-up" style="animation-delay:160ms">
      <div class="hero-stats">
        <div class="stat-cell stat-active">
          <div class="stat-ring">
            <span class="stat-glow active"></span>
            <span class="big-num">{{ String(activeCount).padStart(2, '0') }}</span>
          </div>
          <div class="stat-foot">
            <span class="stat-dot live"></span>
            <span class="serial">活跃</span>
          </div>
        </div>
        <div class="stat-cell stat-archived">
          <div class="stat-ring">
            <span class="stat-glow archived"></span>
            <span class="big-num">{{ String(archivedCount).padStart(2, '0') }}</span>
          </div>
          <div class="stat-foot">
            <span class="stat-dot frozen"></span>
            <span class="serial">归档</span>
          </div>
        </div>
        <div class="stat-cell stat-repos">
          <div class="stat-ring">
            <span class="big-num">{{ String(repos.length).padStart(2, '0') }}</span>
          </div>
          <div class="stat-foot">
            <span class="serial">仓库</span>
          </div>
        </div>
        <div class="stat-cell stat-total">
          <div class="stat-ring">
            <span class="big-num">{{ String(totalSessions).padStart(2, '0') }}</span>
          </div>
          <div class="stat-foot">
            <span class="serial">总数</span>
          </div>
        </div>
      </div>
    </section>

    <!-- Filter strip -->
    <div class="filter-strip fade-up" style="animation-delay:280ms">
      <button
        v-for="f in ['all', 'active', 'archived'] as const"
        :key="f"
        class="filter-pill"
        :class="{ on: filter === f }"
        @click="filter = f"
      >
        <span class="filter-dot" :class="f"></span>
        <span>{{ f === 'all' ? '全部' : f === 'active' ? '活跃' : '归档' }}</span>
        <span class="filter-count">{{ f === 'all' ? totalSessions : f === 'active' ? activeCount : archivedCount }}</span>
      </button>
      <span class="dotline-fill"></span>
      <span class="serial">{{ filteredSessions.length }} 项</span>
      <button class="btn-primary" @click="showCreator = !showCreator">
        {{ showCreator ? '收起' : '新建会话' }}
      </button>
    </div>

    <!-- Creator -->
    <transition name="slide-down">
      <section v-if="showCreator" class="creator card">
        <div class="creator-grid">
          <div class="creator-card">
            <span class="tag">01 · 创建仓库</span>
            <h3 class="creator-title">创建或导入<br /><strong>新仓库</strong></h3>
            <input v-model="newRepoId" class="field-raw" maxlength="32" placeholder="仓库标识（英文/数字/-，≤32位）" />
            <input v-model="newRepoName" class="field-raw" placeholder="显示名（可选）" />
            <div class="creator-actions">
              <button class="btn-outline" :disabled="!newRepoId" @click="onCreateRepo">创建</button>
              <button class="btn-outline" @click="importModalVisible = true">导入工程</button>
              <button class="btn-ghost btn-sm" @click="showRepoManager = true">管理</button>
            </div>
          </div>
          <div class="creator-card">
            <span class="tag green">02 · 开启会话</span>
            <h3 class="creator-title">在既有仓库<br /><strong>开始</strong></h3>
            <select v-model="newSessionRepo" class="field-raw">
              <option v-if="!repos.length" disabled value="">暂无仓库</option>
              <option v-for="r in repos" :key="r.repoId" :value="r.repoId">{{ r.displayName }} · {{ r.repoId }}</option>
            </select>
            <input v-model="newSessionTitle" class="field-raw" placeholder="会话标题（可选）" />
            <button class="btn-primary" :disabled="!newSessionRepo" @click="onCreateSession">
              进入工作室
            </button>
          </div>
        </div>
      </section>
    </transition>

    <!-- Repo Manager Modal -->
    <transition name="fade">
      <div v-if="showRepoManager" class="modal-overlay" @click.self="showRepoManager = false">
        <div class="modal card">
          <div class="modal-header">
            <h3>管理仓库</h3>
            <button class="btn-ghost btn-sm" @click="showRepoManager = false">关闭</button>
          </div>
          <div class="modal-body">
            <div v-if="!repos.length" class="empty-repos">暂无仓库</div>
            <div v-else class="repo-list">
              <div v-for="r in repos" :key="r.repoId" class="repo-item">
                <div class="repo-info">
                  <span class="repo-name">{{ r.displayName }}</span>
                  <code class="repo-id">{{ r.repoId }}</code>
                </div>
                <div class="repo-actions">
                  <button v-if="auth.isAdmin" class="btn-ghost btn-sm" @click="onExportRepo(r.repoId, r.displayName)">导出</button>
                  <button v-if="auth.isAdmin" class="btn-ghost btn-sm" @click="openCopyModal(r.repoId)">复制</button>
                  <button v-if="auth.isAdmin" class="btn-mini-danger btn-sm" @click="confirmDeleteRepo(r.repoId, r.displayName)">删除</button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </transition>

    <!-- Copy Repo Modal -->
    <transition name="fade">
      <div v-if="showCopyModal" class="modal-overlay" @click.self="showCopyModal = false">
        <div class="modal card">
          <div class="modal-header">
            <h3>复制仓库</h3>
            <button class="btn-ghost btn-sm" @click="showCopyModal = false">取消</button>
          </div>
          <div class="modal-body">
            <p class="modal-hint">将 <code>{{ copySourceRepo }}</code> 复制为新仓库：</p>
            <input v-model="copyTargetRepoId" class="field-raw" maxlength="32" placeholder="新仓库标识（英文/数字/-，≤32位）" />
            <input v-model="copyDisplayName" class="field-raw" placeholder="显示名（可选）" />
            <div class="modal-actions">
              <button class="btn-primary" :disabled="!copyTargetRepoId" @click="doCopyRepo">确认复制</button>
            </div>
          </div>
        </div>
      </div>
    </transition>

    <!-- 导入工程弹窗 -->
    <transition name="fade">
      <div v-if="importModalVisible" class="modal-overlay" @click.self="!importLoading && (importModalVisible = false)">
        <div class="modal card import-modal">
          <div class="modal-header">
            <h3>导入工程</h3>
            <button v-if="!importLoading" class="btn-ghost btn-sm" @click="importModalVisible = false">取消</button>
          </div>
          <div class="modal-body import-body">
            <p class="modal-hint">选择本地文件夹导入为仓库，文件夹名自动作为仓库标识</p>
            <div class="import-zone" :class="{ 'is-loading': importLoading }">
              <template v-if="!importLoading">
                <span class="import-icon">📁</span>
                <p class="import-text">点击下方按钮选择文件夹，或拖拽文件夹到此处</p>
                <button class="btn-primary" @click="importFileInput?.click()">选择文件夹</button>
                <input
                  ref="importFileInput"
                  class="import-upload-input"
                  type="file"
                  webkitdirectory
                  directory
                  multiple
                  hidden
                  @change="onImportFiles"
                />
              </template>
              <template v-else>
                <span class="import-spinner"></span>
                <p class="import-text">正在上传并创建仓库…</p>
              </template>
            </div>
          </div>
        </div>
      </div>
    </transition>

    <!-- Session list -->
    <section class="ledger fade-up" style="animation-delay:340ms">
      <div v-if="!filteredSessions.length" class="empty card">
        <span class="empty-icon">—</span>
        <p class="empty-text">暂无会话</p>
        <span class="serial">点击右上「新建会话」开始第一次编码</span>
      </div>

      <div v-else class="entries">
        <article
          v-for="(s, idx) in filteredSessions"
          :key="s.sessionId"
          class="entry card"
          :style="{ animationDelay: 340 + idx * 60 + 'ms' }"
          @click="router.push('/sessions/' + s.sessionId)"
        >
          <div class="entry-l">
            <span class="entry-num">{{ String(idx + 1).padStart(2, '0') }}</span>
            <span class="status-tag" :class="effectiveStatus(s)">
              <span class="status-dot"></span>
              {{ effectiveStatus(s) === 'active' ? '活跃' : '归档' }}
            </span>
          </div>
          <div class="entry-body">
            <h3 class="entry-title">{{ s.title || '未命名会话' }}</h3>
            <div class="entry-meta">
              <span class="meta">
                <span class="serial">仓库</span>
                <code class="mono">{{ s.repoId }}</code>
              </span>
              <span class="meta">
                <span class="serial">ID</span>
                <code class="mono">{{ s.sessionId.slice(0, 8) }}</code>
              </span>
              <span class="meta">
                <span class="serial">更新</span>
                <span class="mono">{{ fmtDate(s.lastActiveAt) }}</span>
              </span>
            </div>
          </div>
          <div class="entry-act" @click.stop>
            <button class="btn-ghost" @click="router.push('/sessions/' + s.sessionId)">
              <span>打开</span>
              <span class="caret">→</span>
            </button>
            <button v-if="effectiveStatus(s) === 'active'" class="btn-mini-danger" @click="onArchive(s.sessionId)">归档</button>
            <button v-else class="btn-mini-danger" @click="onUnarchive(s.sessionId)">恢复</button>
          </div>
        </article>
      </div>
    </section>
  </div>
</template>

<style scoped>
.page {
  padding: 24px 32px 80px;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

/* ====================================================================
   Hero stats — instrumentation panel
   ==================================================================== */
.hero-stats-section {
  padding: 24px 0 8px;
  position: relative;
  z-index: 1;
}

.hero-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 1px;
  background: var(--border);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
}

.stat-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  padding: 28px 16px 22px;
  background: var(--surface);
  position: relative;
}

.stat-ring {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 88px;
  height: 88px;
  border-radius: 50%;
}

/* subtle outer ring */
.stat-ring::before {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: 50%;
  border: 1.5px solid var(--border);
}

.stat-glow {
  position: absolute;
  inset: -6px;
  border-radius: 50%;
  z-index: -1;
  opacity: 0;
}

.stat-glow.active {
  background: radial-gradient(circle, rgba(34, 197, 94, 0.18) 0%, transparent 70%);
  animation: stat-pulse 3.5s var(--ease-out) infinite;
}

.stat-glow.archived {
  background: radial-gradient(circle, rgba(134, 144, 156, 0.12) 0%, transparent 70%);
}

@keyframes stat-pulse {
  0%, 100% { opacity: 0.6; }
  50% { opacity: 1; }
}

.stat-cell .big-num {
  font-family: var(--font-mono);
  font-size: clamp(36px, 3.6vw, 48px);
  font-weight: 700;
  letter-spacing: -0.04em;
  line-height: 1;
  position: relative;
  z-index: 1;
  font-variant-numeric: tabular-nums;
  color: var(--brand);
}

/* Active — green */
.stat-active .big-num {
  color: #16A34A;
}

/* Archived — muted slate */
.stat-archived .big-num {
  color: var(--ink-mute);
}

.stat-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--ink-mute);
}

.stat-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
}

.stat-dot.live {
  background: #16A34A;
  box-shadow: 0 0 6px rgba(22, 163, 74, 0.35);
  animation: stat-pulse 3.5s var(--ease-out) infinite;
}

.stat-dot.frozen { background: var(--ink-mute); }

/* ====================================================================
   Mobile collapse
   ==================================================================== */
@media (max-width: 900px) {
  .hero-stats {
    grid-template-columns: repeat(2, 1fr);
  }
  .stat-cell {
    padding: 20px 14px 18px;
    gap: 10px;
  }
  .stat-ring {
    width: 72px;
    height: 72px;
  }
  .stat-cell .big-num {
    font-size: 32px;
  }
}

/* ====================================================================
   Filter strip
   ==================================================================== */
.filter-strip {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.filter-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 7px 14px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
  color: var(--ink-2);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all var(--dur-fast) var(--ease-out);
}
.filter-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--ink-mute); }
.filter-dot.all { background: var(--brand); }
.filter-dot.active { background: var(--good); }
.filter-dot.archived { background: var(--ink-faint); }
.filter-count {
  padding: 0 8px;
  border-radius: var(--radius-pill);
  background: var(--surface-soft);
  color: var(--ink-mute);
  font-size: 11px;
  min-width: 22px;
  text-align: center;
}
.filter-pill:hover { border-color: var(--brand); color: var(--brand); }
.filter-pill.on { background: var(--brand); color: var(--ink-invert); border-color: var(--brand); }
.filter-pill.on .filter-count { background: rgba(255,255,255,0.18); color: var(--ink-invert); }

/* ====================================================================
   Creator
   ==================================================================== */
.creator { overflow: hidden; }
.creator-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
}
.creator-card {
  padding: 28px 32px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.creator-card + .creator-card { border-left: 1px solid var(--border); }
.creator-title {
  font-family: var(--font-display);
  font-size: 22px;
  font-weight: 600;
  line-height: 1.2;
  margin: 0;
  color: var(--ink);
}
.creator-title strong { color: var(--brand); font-weight: 700; }
.creator-card .btn-outline,
.creator-card .btn-primary { align-self: flex-start; margin-top: 4px; }

.creator-actions {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 4px;
}
.btn-sm { padding: 4px 10px; font-size: 12px; }

/* ====================================================================
   Import modal
   ==================================================================== */
.import-modal {
  width: 480px;
}
.import-body {
  gap: 16px;
}
.import-zone {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding: 40px 24px;
  border: 2px dashed var(--border);
  border-radius: var(--radius);
  background: var(--surface-soft);
  text-align: center;
  transition: all var(--dur-fast) var(--ease-out);
  min-height: 180px;
}
.import-zone.is-loading {
  border-color: var(--brand);
  background: var(--surface);
}
.import-icon {
  font-size: 40px;
  line-height: 1;
}
.import-text {
  font-size: 13px;
  color: var(--ink-2);
  margin: 0;
}
.import-spinner {
  width: 40px;
  height: 40px;
  border: 3px solid var(--border);
  border-top-color: var(--brand);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}

/* ====================================================================
   Modal
   ==================================================================== */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}
.modal {
  width: 420px;
  max-width: 90vw;
  max-height: 80vh;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid var(--border);
}
.modal-header h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}
.modal-body {
  padding: 16px 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow-y: auto;
}
.modal-hint {
  margin: 0;
  color: var(--ink-2);
  font-size: 13px;
}
.modal-hint code {
  background: var(--surface-soft);
  padding: 2px 6px;
  border-radius: 4px;
  font-family: var(--font-mono);
  font-size: 12px;
}
.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}

.empty-repos {
  text-align: center;
  color: var(--ink-mute);
  padding: 24px;
}

.repo-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.repo-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px;
  background: var(--surface-soft);
  border-radius: var(--radius);
}
.repo-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.repo-name {
  font-weight: 500;
  font-size: 14px;
}
.repo-id {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--ink-mute);
  background: var(--surface);
  padding: 2px 6px;
  border-radius: 4px;
}
.repo-actions {
  display: flex;
  gap: 6px;
}

.fade-enter-active, .fade-leave-active {
  transition: opacity 200ms ease;
}
.fade-enter-from, .fade-leave-to {
  opacity: 0;
}

.slide-down-enter-active, .slide-down-leave-active {
  transition: all 360ms var(--ease-out);
  overflow: hidden;
}
.slide-down-enter-from, .slide-down-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

/* ====================================================================
   Ledger
   ==================================================================== */
.empty {
  padding: 64px 24px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.empty-icon { font-size: 56px; color: var(--ink-faint); }
.empty-text {
  font-size: 18px;
  font-weight: 600;
  color: var(--ink-2);
  margin: 4px 0;
}

.entries { display: flex; flex-direction: column; gap: 10px; }
.entry {
  display: grid;
  grid-template-columns: 132px 1fr auto;
  gap: 20px;
  align-items: center;
  padding: 18px 22px;
  cursor: pointer;
  animation: fade-up var(--dur-slow) var(--ease-out) both;
}
.entry-l {
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: flex-start;
}
.entry-num {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--ink-mute);
}
.status-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border-radius: var(--radius-pill);
  font-size: 11px;
  font-weight: 500;
}
.status-tag.active { background: var(--good-soft); color: var(--good); }
.status-tag.archived { background: var(--surface-soft); color: var(--ink-mute); border: 1px solid var(--border); }
.status-dot { width: 5px; height: 5px; border-radius: 50%; background: currentColor; }

.entry-body { min-width: 0; }
.entry-title {
  font-family: var(--font-display);
  font-size: 18px;
  font-weight: 600;
  margin: 0 0 6px;
  color: var(--ink);
}
.entry-meta { display: flex; flex-wrap: wrap; gap: 18px; font-size: 12px; color: var(--ink-mute); }
.meta { display: inline-flex; align-items: baseline; gap: 6px; }
.meta .serial { font-size: 10px; letter-spacing: 0.06em; }
.meta code, .meta .mono { font-family: var(--font-mono); color: var(--ink-2); font-size: 11px; }

.entry-act { display: flex; flex-direction: column; gap: 6px; align-items: flex-end; }
.entry-act .btn-ghost { padding: 5px 14px; font-size: 12px; gap: 4px; }

/* ====================================================================
   Mobile
   ==================================================================== */
@media (max-width: 900px) {
  .page { padding: 16px 16px 48px; }
  .hero-stats-section { padding: 16px 0 8px; }
  .creator-grid { grid-template-columns: 1fr; }
  .creator-card + .creator-card { border-left: 0; border-top: 1px solid var(--border); }
  .entry { grid-template-columns: 1fr; padding: 16px 18px; }
  .entry-act { flex-direction: row; }
}

/* ====================================================================
   Mobile — max-width 768px
   ==================================================================== */
@media (max-width: 768px) {
  .page { padding: 12px 12px 64px; gap: 16px; }

  /* --- filter strip --- */
  .filter-strip { gap: 8px; }
  .filter-pill {
    flex: 1 1 auto;
    min-width: 0;
    justify-content: center;
    font-size: 11px;
    padding: 6px 10px;
  }
  .filter-strip .btn-primary {
    width: 100%;
    justify-content: center;
  }
  .filter-strip .dotline-fill { display: none; }
  .filter-strip .serial { display: none; }

  /* --- creator --- */
  .creator-card { padding: 18px 16px; gap: 10px; }
  .creator-title { font-size: 17px; }
  .creator-card .btn-primary,
  .creator-card .btn-outline { width: 100%; justify-content: center; }
  .creator-actions { flex-direction: column; width: 100%; }
  .creator-actions .btn-outline,
  .creator-actions .btn-ghost { width: 100%; justify-content: center; }

  /* --- entry cards --- */
  .entry { padding: 14px 14px; gap: 8px; }
  .entry-l {
    flex-direction: row;
    justify-content: space-between;
    align-items: center;
    gap: 8px;
  }
  .entry-num { font-size: 11px; }
  .entry-title { font-size: 16px; margin: 0 0 4px; }
  .entry-meta { gap: 10px; flex-wrap: wrap; }
  .meta { gap: 4px; }
  .meta .mono { font-size: 10px; }
  .entry-act {
    flex-direction: row;
    justify-content: stretch;
    gap: 8px;
  }
  .entry-act .btn-ghost,
  .entry-act .btn-mini-danger {
    flex: 1;
    text-align: center;
    justify-content: center;
  }

  /* --- import modal --- */
  .import-zone { padding: 24px 16px; min-height: 140px; }
  .import-modal { width: 100vw; }

  /* --- modal --- */
  .modal-overlay {
    align-items: flex-end;
  }
  .modal {
    width: 100vw;
    max-width: 100vw;
    max-height: 85vh;
    border-radius: var(--radius) var(--radius) 0 0;
  }
  .modal-header { padding: 14px 16px; }
  .modal-body { padding: 14px 16px; gap: 10px; }
  .repo-item { flex-direction: column; align-items: flex-start; gap: 8px; }
  .repo-actions { width: 100%; justify-content: flex-end; }
}

/* ====================================================================
   Mobile — max-width 480px (small phones)
   ==================================================================== */
@media (max-width: 480px) {
  .stat-cell { padding: 14px 8px 12px; gap: 6px; }
  .stat-ring { width: 56px; height: 56px; }
  .stat-ring::before { border-width: 1px; }
  .stat-cell .big-num { font-size: 26px; }
  .stat-foot { font-size: 10px; gap: 4px; }
  .entry-title { font-size: 15px; }
  .entry-meta { gap: 6px; font-size: 11px; }
}
</style>