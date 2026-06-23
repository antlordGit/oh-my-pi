<script setup lang="ts">
import { onMounted, ref, computed, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useMessage, useDialog, NSteps, NStep, NInput, NRadioGroup, NRadio, NSelect, NCheckbox } from 'naive-ui'
import { listSessions, createSession, archive, unarchive, deleteArchivedSession, type SessionSummary } from '@/api/session'
import {
  listRepos,
  createRepo,
  importRepo,
  cloneRepo,
  initRepo,
  copyRepo,
  deleteRepo,
  exportRepo,
  type Repo,
} from '@/api/repo'

const router = useRouter()
const auth = useAuthStore()
const msg = useMessage()
const dialog = useDialog()

const sessions = ref<SessionSummary[]>([])
const repos = ref<Repo[]>([])
const loading = ref(false)
// 单 toggle：true 时隐藏归档会话（默认 false，显示全部）
const hideArchived = ref(false)
// 当前选中的仓库 Tab：'all' 表示全部；否则为具体 repoId
const activeRepoTab = ref<string>('all')

const newRepoId = ref('')
const newRepoName = ref('')
const newSessionRepo = ref<string>('')
const newSessionTitle = ref('')
const showCreator = ref(false)
const showRepoManager = ref(false)

// ========================================================================
// 导入工程状态
// ========================================================================
const importLoading = ref(false)
const importFileInput = ref<HTMLInputElement | null>(null)

// ========================================================================
// 会话创建向导（三策略：import / clone / template）
// ========================================================================
type WizardStrategy = 'import' | 'clone' | 'template'
const wizardVisible = ref(false)
const wizardStep = ref<1 | 2 | 3>(1)
const wizardStrategy = ref<WizardStrategy>('import')
const wizardStartSession = ref(true)  // 是否在仓库建好后立即开启会话

// 表单字段 — import（共用 importFileInput -> wizardImportedRepoId 由 onImportFiles 填入）
const wizImportRepoId = ref('')
const wizImportDisplayName = ref('')

const wizCloneRepoId = ref('')
const wizCloneUrl = ref('')
const wizCloneBranch = ref('')
const wizCloneDepth = ref<number | null>(null)
const wizCloneUsername = ref('')
const wizClonePassword = ref('')

const wizTemplateRepoId = ref('')
const wizTemplateKind = ref<'frontend' | 'backend'>('frontend')
const wizTemplateDisplayName = ref('')

const wizSessionTitle = ref('')

const wizardLoading = ref(false)
const wizardError = ref('')

/** 仓库标识验证规则：英文、数字、-，最长 32 位 */
const REPO_ID_RE = /^[A-Za-z0-9-]{1,32}$/
const GIT_URL_RE = /^(https?:\/\/|git:\/\/).+/i

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

/**
 * 处理文件夹选择和导入。
 * 当在向导内调用时（wizardVisible=true），把生成的 repoId 写回 wizImportRepoId，
 * 让向导走「下一步」时可以直接复用；否则按原行为关掉独立 modal。
 */
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
    if (wizardVisible.value) {
      // 向导内：写回 repoId，留在向导让用户继续填会话名
      wizImportRepoId.value = repoId
      wizImportDisplayName.value = folderName
    }
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

const filteredSessions = computed(() => {
  let list = sessions.value
  if (hideArchived.value) list = list.filter(s => s.status !== 'archived')
  if (activeRepoTab.value !== 'all') list = list.filter(s => s.repoId === activeRepoTab.value)
  return list
})

// 各 repo 在当前过滤条件下的会话数（用于 Tab 上角标）
const repoCounts = computed<Record<string, number>>(() => {
  const base = hideArchived.value
    ? sessions.value.filter(s => s.status !== 'archived')
    : sessions.value
  const out: Record<string, number> = {}
  for (const s of base) out[s.repoId] = (out[s.repoId] || 0) + 1
  return out
})

// 用于 Tab 展示的仓库列表：仅显示有会话的仓库
const visibleRepos = computed<Repo[]>(() => {
  return repos.value.filter(r => (repoCounts.value[r.repoId] || 0) > 0)
})

// "全部" Tab 角标：在当前 filter 下的总会话数
const totalFilteredCount = computed(() =>
  Object.values(repoCounts.value).reduce((s, n) => s + n, 0),
)

// 当前选中的 repo tab 不在可见集合中时，自动回退到 "all"
watch(visibleRepos, (list) => {
  if (activeRepoTab.value !== 'all' && !list.some(r => r.repoId === activeRepoTab.value)) {
    activeRepoTab.value = 'all'
  }
}, { immediate: true })

// Tab 切换 / 数据变化时，把激活的 Tab 自动滚动到视口中央，避免在尾部仓库时看不到
const repoTabsEl = ref<HTMLElement | null>(null)
watch(activeRepoTab, async () => {
  await nextTick()
  const container = repoTabsEl.value
  if (!container) return
  const target = container.querySelector<HTMLElement>('.repo-tab.on')
  if (!target) return
  const offset = target.offsetLeft - (container.clientWidth - target.clientWidth) / 2
  container.scrollTo({ left: Math.max(0, offset), behavior: 'smooth' })
})

// 让鼠标滚轮在 Tab 条上时映射为横向滚动（无触控板用户也能用）
function onRepoTabsWheel(e: WheelEvent) {
  const el = repoTabsEl.value
  if (!el || el.scrollWidth <= el.clientWidth) return
  // 仅在纯垂直滚动时拦截，避免触控板自然横滚被反向
  if (e.deltaY !== 0 && Math.abs(e.deltaY) > Math.abs(e.deltaX)) {
    e.preventDefault()
    el.scrollLeft += e.deltaY
  }
}

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

// ========================================================================
// 会话创建向导逻辑
// ========================================================================
function openWizard() {
  // 重置表单状态
  wizardStep.value = 1
  wizardStrategy.value = 'import'
  wizardStartSession.value = true
  wizImportRepoId.value = ''
  wizImportDisplayName.value = ''
  wizCloneRepoId.value = ''
  wizCloneUrl.value = ''
  wizCloneBranch.value = ''
  wizCloneDepth.value = null
  wizCloneUsername.value = ''
  wizClonePassword.value = ''
  wizTemplateRepoId.value = ''
  wizTemplateKind.value = 'frontend'
  wizTemplateDisplayName.value = ''
  wizSessionTitle.value = ''
  wizardError.value = ''
  wizardVisible.value = true
}

function closeWizard() {
  if (wizardLoading.value) return
  wizardVisible.value = false
}

function pickStrategy(s: WizardStrategy) {
  wizardStrategy.value = s
  wizardStep.value = 2
  wizardError.value = ''
}

/** Step 2 → Step 3 前校验 */
function validateStep2(): string | null {
  if (wizardStrategy.value === 'import') {
    if (!wizImportRepoId.value) return '请先选择要导入的文件夹'
  } else if (wizardStrategy.value === 'clone') {
    if (!wizCloneRepoId.value) return '请输入仓库标识'
    if (!REPO_ID_RE.test(wizCloneRepoId.value)) return '仓库标识只能包含英文、数字和-，且不超过32位'
    if (!wizCloneUrl.value) return '请输入 Git URL'
    if (!GIT_URL_RE.test(wizCloneUrl.value)) return 'Git URL 必须以 http(s):// 或 git:// 开头'
  } else if (wizardStrategy.value === 'template') {
    if (!wizTemplateRepoId.value) return '请输入仓库标识'
    if (!REPO_ID_RE.test(wizTemplateRepoId.value)) return '仓库标识只能包含英文、数字和-，且不超过32位'
  }
  if (wizardStartSession.value && !wizSessionTitle.value.trim()) {
    return '请输入会话名称'
  }
  return null
}

async function submitWizard() {
  const err = validateStep2()
  if (err) {
    wizardError.value = err
    return
  }
  wizardStep.value = 3
  wizardLoading.value = true
  wizardError.value = ''
  try {
    let repoId: string
    if (wizardStrategy.value === 'import') {
      // import 路径：repoId 已在 onImportFiles 中创建（文件上传完成后 set wizImportRepoId）
      // 这里不需要再创建仓库，直接复用 importRepo 的结果
      repoId = wizImportRepoId.value
    } else if (wizardStrategy.value === 'clone') {
      await cloneRepo(
        wizCloneRepoId.value,
        wizCloneUrl.value.trim(),
        wizCloneBranch.value.trim() || undefined,
        wizCloneDepth.value ?? undefined,
        wizCloneUsername.value.trim() || undefined,
        wizClonePassword.value.trim() || undefined,
      )
      repoId = wizCloneRepoId.value
    } else {
      await initRepo(
        wizTemplateRepoId.value,
        wizTemplateKind.value,
        wizTemplateDisplayName.value || wizTemplateRepoId.value,
      )
      repoId = wizTemplateRepoId.value
    }

    if (wizardStartSession.value && wizSessionTitle.value.trim()) {
      const s = await createSession(repoId, wizSessionTitle.value.trim())
      msg.success('会话已开启')
      wizardVisible.value = false
      await refresh()
      router.push(`/sessions/${s.sessionId}`)
    } else {
      msg.success('仓库已创建')
      wizardVisible.value = false
      await refresh()
    }
  } catch (e: any) {
    wizardError.value = e?.response?.data?.error || e?.message || '操作失败'
    // 失败回 step 2 保留输入
    wizardStep.value = 2
  } finally {
    wizardLoading.value = false
  }
}

async function onCreateSession() {
  if (!newSessionRepo.value) return msg.warning('请选择仓库')
  const title = newSessionTitle.value.trim()
  if (!title) return msg.warning('请输入会话名称')
  try {
    const s = await createSession(newSessionRepo.value, title)
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

// ========================================================================
// 删除（单条 / 批量）—— 与管理台一致的两阶段安全词确认
// ========================================================================
const selectedIds = ref<Set<string>>(new Set())
const MAX_BATCH_DELETE = 50
const batchDeleting = ref(false)

const archivedPageIds = computed(() =>
  filteredSessions.value.filter(s => effectiveStatus(s) === 'archived').map(s => s.sessionId)
)
const allCurrentSelected = computed(() =>
  archivedPageIds.value.length > 0
  && archivedPageIds.value.every(id => selectedIds.value.has(id))
)
const someCurrentSelected = computed(() =>
  archivedPageIds.value.some(id => selectedIds.value.has(id)) && !allCurrentSelected.value
)

function toggleSelectAll() {
  if (allCurrentSelected.value) {
    for (const id of archivedPageIds.value) selectedIds.value.delete(id)
  } else {
    if (selectedIds.value.size + archivedPageIds.value.length > MAX_BATCH_DELETE) {
      msg.warning(`单批最多 ${MAX_BATCH_DELETE} 条，请分批操作`)
      const room = Math.max(0, MAX_BATCH_DELETE - selectedIds.value.size)
      for (const id of archivedPageIds.value.slice(0, room)) selectedIds.value.add(id)
    } else {
      for (const id of archivedPageIds.value) selectedIds.value.add(id)
    }
  }
  selectedIds.value = new Set(selectedIds.value)
}

function toggleSelectOne(id: string) {
  if (selectedIds.value.has(id)) selectedIds.value.delete(id)
  else {
    if (selectedIds.value.size >= MAX_BATCH_DELETE) { msg.warning(`单批最多 ${MAX_BATCH_DELETE} 条`); return }
    selectedIds.value.add(id)
  }
  selectedIds.value = new Set(selectedIds.value)
}

function clearSelection() { selectedIds.value = new Set() }

function confirmDelete(id: string) {
  const row = filteredSessions.value.find(s => s.sessionId === id)
  const label = row?.title || '未命名会话'
  dialog.warning({
    title: '删除会话',
    content: `确认删除会话「${label}」？`,
    positiveText: '确认删除',
    negativeText: '取消',
    closable: true,
    onPositiveClick: () => { void deleteOne(id) },
  })
}

async function deleteOne(id: string) {
  const row = filteredSessions.value.find(s => s.sessionId === id)
  if (row) (row as any)._deleting = true
  try {
    await deleteArchivedSession(id)
    msg.success('已删除')
    selectedIds.value.delete(id)
    selectedIds.value = new Set(selectedIds.value)
    // 立即从列表移除，避免重新加载
    sessions.value = sessions.value.filter(s => s.sessionId !== id)
  } catch (e: any) {
    if (row) (row as any)._deleting = false
    msg.error(e?.response?.data?.error || e?.response?.data?.message || '删除失败')
  }
}

function confirmBatchDelete() {
  const ids = [...selectedIds.value]
  if (ids.length === 0) return msg.warning('请先勾选要删除的归档会话')
  if (ids.length > MAX_BATCH_DELETE) return msg.warning(`单批最多 ${MAX_BATCH_DELETE} 条`)
  dialog.error({
    title: `批量删除 ${ids.length} 个会话`,
    content: `即将彻底删除 ${ids.length} 个已归档会话。\n\n会一并清理：\n  · 数据库主行\n  · 3 张审计表记录\n  · 磁盘会话文件\n\n此操作不可恢复，是否继续？`,
    positiveText: `删除 ${ids.length} 个`,
    negativeText: '取消',
    onPositiveClick: () => { void batchDelete(ids) },
  })
}

async function batchDelete(ids: string[]) {
  batchDeleting.value = true
  let success = 0, failed = 0
  await Promise.allSettled(ids.map(async (id) => {
    try { await deleteArchivedSession(id); success++ }
    catch { failed++ }
  }))
  batchDeleting.value = false
  selectedIds.value = new Set()
  if (success > 0) msg.success(`已删除 ${success} 个${failed > 0 ? `，${failed} 个失败` : ''}`)
  else if (failed > 0) msg.error(`全部 ${failed} 个删除失败`)
  refresh()
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
    <!-- Meta strip — terminal instrumentation -->
    <div class="meta-strip fade-up" style="animation-delay:160ms">
      <div class="meta-cell">
        <span class="meta-key">仓库</span>
        <span class="meta-val mono">{{ repos.length.toString().padStart(2, '0') }}</span>
      </div>
      <span class="meta-sep">·</span>
      <div class="meta-cell">
        <span class="meta-key">会话</span>
        <span class="meta-val mono">{{ totalSessions.toString().padStart(2, '0') }}</span>
      </div>
      <span class="meta-sep">·</span>
      <div class="meta-cell">
        <span class="meta-key">活跃</span>
        <span class="meta-val mono" :class="{ ok: activeCount > 0 }">{{ activeCount.toString().padStart(2, '0') }}</span>
      </div>
      <span class="meta-sep">·</span>
      <div class="meta-cell">
        <span class="meta-key">归档</span>
        <span class="meta-val mono" :class="{ ok: archivedCount > 0 }">{{ archivedCount.toString().padStart(2, '0') }}</span>
      </div>
      <span class="meta-spacer"></span>
      <button
        class="archive-toggle"
        :class="{ on: hideArchived }"
        @click="hideArchived = !hideArchived"
        :aria-pressed="hideArchived"
      >
        <span class="archive-toggle-dot"></span>
        <span>仅看活跃</span>
      </button>
    </div>

    <!-- Filter strip — 仅保留新建入口，过滤已下放到 meta-strip 与 repo-tabs -->
    <div class="filter-strip fade-up" style="animation-delay:280ms">
      <span class="serial">{{ filteredSessions.length }} 项 · 当前：{{ activeRepoTab === 'all' ? '全部仓库' : (visibleRepos.find(r => r.repoId === activeRepoTab)?.displayName || activeRepoTab) }}</span>
      <span class="dotline-fill"></span>
      <button class="btn-primary" @click="openWizard">新建仓库</button>
    </div>

    <!-- Repo Tabs -->
    <div
      v-if="visibleRepos.length > 0"
      ref="repoTabsEl"
      class="repo-tabs fade-up"
      style="animation-delay:320ms"
      @wheel="onRepoTabsWheel"
    >
      <button
        class="repo-tab"
        :class="{ on: activeRepoTab === 'all' }"
        @click="activeRepoTab = 'all'"
      >
        <span class="repo-tab-name">全部仓库</span>
        <span class="repo-tab-count mono">{{ totalFilteredCount }}</span>
      </button>
      <button
        v-for="r in visibleRepos"
        :key="r.repoId"
        class="repo-tab"
        :class="{ on: activeRepoTab === r.repoId }"
        :title="r.displayName + ' · ' + r.repoId"
        @click="activeRepoTab = r.repoId"
      >
        <span class="repo-tab-name">{{ r.displayName || r.repoId }}</span>
        <span class="repo-tab-count mono">{{ repoCounts[r.repoId] || 0 }}</span>
      </button>
    </div>

    <!-- Creator — 在既有仓库上开新会话；新建仓库走右上角向导按钮 -->
    <section class="creator card fade-up" style="animation-delay:340ms">
      <!-- 在既有仓库上开新会话 -->
      <div class="quick-session">
        <span class="tag green">在既有仓库上开新会话</span>
        <div class="quick-session-row">
          <select v-model="newSessionRepo" class="field-raw">
            <option v-if="!repos.length" disabled value="">暂无仓库</option>
            <option v-for="r in repos" :key="r.repoId" :value="r.repoId">{{ r.displayName }} · {{ r.repoId }}</option>
          </select>
          <input v-model="newSessionTitle" class="field-raw" placeholder="会话名称（必填）" />
          <button class="btn-primary" :disabled="!newSessionRepo || !newSessionTitle.trim()" @click="onCreateSession">
            进入工作室
          </button>
        </div>
      </div>
    </section>

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

    <!-- 会话创建向导 modal（三策略：empty / clone / template） -->
    <transition name="fade">
      <div v-if="wizardVisible" class="modal-overlay" @click.self="closeWizard">
        <div class="modal card wizard-modal">
          <div class="modal-header">
            <h3>{{ wizardStrategy === 'import' ? '导入工程' : wizardStrategy === 'clone' ? '克隆仓库' : '初始化模板' }}</h3>
            <button v-if="!wizardLoading" class="btn-ghost btn-sm" @click="closeWizard">取消</button>
          </div>

          <div class="modal-body wizard-body">
            <NSteps :current="wizardStep" size="small" class="wizard-steps">
              <NStep title="选择策略" />
              <NStep title="配置仓库" />
              <NStep title="执行" />
            </NSteps>

            <!-- Step 1: 策略选择 -->
            <div v-if="wizardStep === 1" class="wizard-step">
              <p class="modal-hint">选择仓库的初始化方式</p>
              <div class="wizard-strategy-grid">
                <button
                  class="wizard-strategy-card"
                  :class="{ on: wizardStrategy === 'import' }"
                  @click="pickStrategy('import')"
                >
                  <span class="strategy-icon">⇪</span>
                  <h4>导入工程</h4>
                  <p>上传本地文件夹</p>
                </button>
                <button
                  class="wizard-strategy-card"
                  :class="{ on: wizardStrategy === 'clone' }"
                  @click="pickStrategy('clone')"
                >
                  <span class="strategy-icon">⎇</span>
                  <h4>克隆仓库</h4>
                  <p>git clone 远程仓库</p>
                </button>
                <button
                  class="wizard-strategy-card"
                  :class="{ on: wizardStrategy === 'template' }"
                  @click="pickStrategy('template')"
                >
                  <span class="strategy-icon">◧</span>
                  <h4>初始化模板</h4>
                  <p>前端 / 后端骨架</p>
                </button>
              </div>
            </div>

            <!-- Step 2: 表单 -->
            <div v-if="wizardStep === 2" class="wizard-step">
              <!-- import 表单 -->
              <template v-if="wizardStrategy === 'import'">
                <p class="wizard-hint">选择本地文件夹上传，文件夹名将自动作为仓库标识。</p>
                <div v-if="!wizImportRepoId" class="wizard-import-zone" :class="{ 'is-loading': importLoading }">
                  <template v-if="!importLoading">
                    <span class="import-icon">📁</span>
                    <p class="import-text">点击下方按钮选择文件夹</p>
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
                    <p class="import-text">正在上传…</p>
                  </template>
                </div>
                <div v-else class="wizard-import-done">
                  <span class="wizard-import-tick">✓</span>
                  <div class="wizard-import-info">
                    <span class="wizard-import-label">已导入</span>
                    <code class="wizard-import-repo">{{ wizImportRepoId }}</code>
                  </div>
                  <button class="btn-ghost btn-sm" @click="wizImportRepoId = ''; wizImportDisplayName = ''">重选</button>
                </div>
              </template>

              <!-- clone 表单 -->
              <template v-if="wizardStrategy === 'clone'">
                <label class="wizard-label">仓库标识
                  <input v-model="wizCloneRepoId" class="field-raw" maxlength="32" placeholder="英文/数字/-，≤32位" />
                </label>
                <label class="wizard-label">Git URL
                  <input v-model="wizCloneUrl" class="field-raw" placeholder="http://10.126.2.60:3000/root/test.git" />
                </label>
                <div class="wizard-auth">
                  <label class="wizard-label wizard-label-half">用户名（可选）
                    <input v-model="wizCloneUsername" class="field-raw" placeholder="GitLab 用户名" />
                  </label>
                  <label class="wizard-label wizard-label-half">访问令牌 / 密码（可选）
                    <input v-model="wizClonePassword" class="field-raw" type="password" placeholder="Personal Access Token" />
                  </label>
                </div>
                <p class="wizard-hint">支持 http / https / git scheme，含内网 GitLab。私有仓库认证信息仅用于本次克隆，不会持久化。</p>
              </template>

              <!-- template 表单 -->
              <template v-if="wizardStrategy === 'template'">
                <label class="wizard-label">仓库标识
                  <input v-model="wizTemplateRepoId" class="field-raw" maxlength="32" placeholder="英文/数字/-，≤32位" />
                </label>
                <label class="wizard-label">模板类型
                  <NRadioGroup v-model:value="wizTemplateKind">
                    <NRadio value="frontend">前端 · Vue 3 + Vite + Pinia</NRadio>
                    <NRadio value="backend">后端 · Spring Boot 3.3.5 + JPA + Redis</NRadio>
                  </NRadioGroup>
                </label>
                <label class="wizard-label">显示名（可选）
                  <input v-model="wizTemplateDisplayName" class="field-raw" placeholder="例如 我的后端服务" />
                </label>
              </template>

              <!-- 是否开新会话 -->
              <div class="wizard-divider"></div>
              <NCheckbox v-model:checked="wizardStartSession">建好仓库后立即开启会话</NCheckbox>
              <label v-if="wizardStartSession" class="wizard-label" style="margin-top:8px">
                <input v-model="wizSessionTitle" class="field-raw" placeholder="会话名称（必填）" />
              </label>

              <div v-if="wizardError" class="wizard-error">{{ wizardError }}</div>

              <div class="wizard-actions">
                <button class="btn-ghost" @click="wizardStep = 1; wizardError = ''">上一步</button>
                <button class="btn-primary" @click="submitWizard">
                  {{ wizardStrategy === 'import' && !wizardStartSession ? '完成' : '下一步' }}
                </button>
              </div>
            </div>

            <!-- Step 3: 执行 -->
            <div v-if="wizardStep === 3" class="wizard-step wizard-step-loading">
              <template v-if="wizardLoading">
                <span class="import-spinner"></span>
                <p class="wizard-hint">正在执行，请稍候…</p>
                <p class="wizard-hint wizard-hint-dim">
                  {{ wizardStrategy === 'clone' ? 'git clone 可能耗时 1-3 分钟' : '通常在 5 秒内完成' }}
                </p>
              </template>
              <template v-else>
                <p class="wizard-hint">执行完成。</p>
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
        <p class="empty-text">
          {{ activeRepoTab !== 'all'
              ? '该仓库下没有匹配会话'
              : (hideArchived ? '没有活跃会话' : '暂无会话') }}
        </p>
        <span class="serial">
          {{ sessions.length === 0 ? '点击右上「新建会话」开始第一次编码' : '调整过滤条件试试' }}
        </span>
      </div>

      <div v-else class="entries">
        <!-- 批量删除工具栏（仅在有选择时浮出） -->
        <Transition name="batchbar">
          <div v-if="selectedIds.size > 0" class="batch-bar">
            <span class="batch-count mono">
              已选 <strong>{{ selectedIds.size }}</strong> / {{ MAX_BATCH_DELETE }}
            </span>
            <span class="batch-sep">·</span>
            <span class="batch-hint">仅可对归档会话执行删除</span>
            <span class="batch-spacer"></span>
            <button class="btn-ghost btn-sm" @click.stop="clearSelection">清空选择</button>
            <button
              class="btn-mini-danger"
              :disabled="batchDeleting"
              @click.stop="confirmBatchDelete"
            >{{ batchDeleting ? '删除中…' : `批量删除 ${selectedIds.size} 个` }}</button>
          </div>
        </Transition>

        <article
          v-for="(s, idx) in filteredSessions"
          :key="s.sessionId"
          class="entry card"
          :class="{ 'is-selected': selectedIds.has(s.sessionId), 'is-deleting': (s as any)._deleting }"
          :style="{ animationDelay: 340 + idx * 60 + 'ms' }"
          @click="router.push('/sessions/' + s.sessionId)"
        >
          <label
            v-if="effectiveStatus(s) === 'archived'"
            class="entry-check"
            :title="selectedIds.has(s.sessionId) ? '取消选择' : '选择以便批量删除'"
            @click.stop
          >
            <input
              type="checkbox"
              :checked="selectedIds.has(s.sessionId)"
              :disabled="(s as any)._deleting || (!selectedIds.has(s.sessionId) && selectedIds.size >= MAX_BATCH_DELETE)"
              @change="toggleSelectOne(s.sessionId)"
            />
          </label>
          <div class="entry-main">
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
                <span class="meta" data-label="仓库">
                  <span class="serial">仓库</span>
                  <code class="mono">{{ s.repoId }}</code>
                </span>
                <span class="meta" data-label="ID">
                  <span class="serial">ID</span>
                  <code class="mono">{{ s.sessionId.slice(0, 8) }}</code>
                </span>
                <span class="meta" data-label="更新">
                  <span class="serial">更新</span>
                  <span class="mono">{{ fmtDate(s.lastActiveAt) }}</span>
                </span>
              </div>
            </div>
          </div>
          <div class="entry-act" @click.stop>
            <button class="btn-ghost" @click="router.push('/sessions/' + s.sessionId)">
              <span>打开</span>
              <span class="caret">→</span>
            </button>
            <button
              v-if="effectiveStatus(s) === 'active'"
              class="btn-mini-danger"
              @click="onArchive(s.sessionId)"
            >归档</button>
            <button
              v-else
              class="btn-mini-danger"
              :disabled="(s as any)._deleting"
              @click="onUnarchive(s.sessionId)"
            >{{ (s as any)._deleting ? '删除中…' : '恢复' }}</button>
            <button
              v-if="effectiveStatus(s) === 'archived'"
              class="btn-mini-danger btn-mini-danger--ghost"
              :disabled="(s as any)._deleting"
              :title="`删除会话 ${s.sessionId.slice(0, 8)}`"
              @click="confirmDelete(s.sessionId)"
            >{{ (s as any)._deleting ? '删除中…' : '删除' }}</button>
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
/* ====================================================================
   Meta strip — 压缩版状态指示器
   ==================================================================== */
.meta-strip {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 18px;
  background: var(--surface-soft);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-size: 11px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}
.meta-cell {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  color: var(--ink-mute);
}
.meta-key {
  font-weight: 500;
  letter-spacing: 0.04em;  /* 中文标签不需要字母间距 */
}
.meta-val {
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0;
  text-transform: none;
  color: var(--ink);
}
.meta-val.ok { color: #16A34A; }
.meta-sep { color: var(--ink-faint); }
.meta-spacer { flex: 1; }

.archive-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  background: transparent;
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
  color: var(--ink-2);
  font-size: 11px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  font-weight: 500;
  cursor: pointer;
  transition: all var(--dur-fast) var(--ease-out);
}
.archive-toggle:hover {
  border-color: var(--brand);
  color: var(--brand);
}
.archive-toggle.on {
  background: var(--brand);
  color: var(--ink-invert);
  border-color: var(--brand);
}
.archive-toggle-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ink-faint);
  transition: all var(--dur-fast) var(--ease-out);
}
.archive-toggle.on .archive-toggle-dot {
  background: var(--ink-invert);
  box-shadow: 0 0 6px rgba(255, 255, 255, 0.5);
}

/* ====================================================================
   Mobile collapse
   ==================================================================== */
@media (max-width: 900px) {
  .meta-strip {
    flex-wrap: wrap;
    gap: 10px;
  }
  .meta-spacer { flex-basis: 100%; height: 0; }
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

/* ====================================================================
   Repo Tabs — 按仓库切换会话
   ==================================================================== */
.repo-tabs {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: nowrap;
  overflow-x: auto;
  overflow-y: hidden;
  /* 边缘渐隐：提示左右两侧还有更多 Tab */
  -webkit-mask-image: linear-gradient(to right, transparent, #000 24px, #000 calc(100% - 24px), transparent);
          mask-image: linear-gradient(to right, transparent, #000 24px, #000 calc(100% - 24px), transparent);
  /* 流畅滚动 */
  scroll-behavior: smooth;
  scrollbar-width: none;          /* Firefox */
  -ms-overflow-style: none;       /* IE / 旧 Edge */
  border-bottom: 1px solid var(--border);
  padding-bottom: 0;
  padding-left: 4px;
  padding-right: 4px;
  margin-bottom: 4px;
}
.repo-tabs::-webkit-scrollbar { display: none; } /* WebKit */
.repo-tab { flex-shrink: 0; }     /* 禁止 Tab 被挤压变形 */
.repo-tab {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  background: transparent;
  border: 0;
  border-bottom: 2px solid transparent;
  color: var(--ink-2);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: color var(--dur-fast) var(--ease-out), border-color var(--dur-fast) var(--ease-out), background var(--dur-fast) var(--ease-out);
  border-radius: 0;
  white-space: nowrap;
  max-width: 240px;
}
.repo-tab-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 180px;
}
.repo-tab:hover {
  color: var(--brand);
  background: var(--brand-soft);
}
.repo-tab.on {
  color: var(--brand);
  border-bottom-color: var(--brand);
  background: var(--brand-soft);
}
.repo-tab-count {
  min-width: 20px;
  padding: 0 6px;
  border-radius: var(--radius-pill);
  background: var(--surface-soft);
  color: var(--ink-mute);
  font-size: 11px;
  line-height: 18px;
  text-align: center;
  transition: background var(--dur-fast) var(--ease-out), color var(--dur-fast) var(--ease-out);
}
.repo-tab.on .repo-tab-count {
  background: var(--brand);
  color: var(--ink-invert);
}

/* ====================================================================
   Creator — 在既有仓库上开新会话（仅快速入口）
   ==================================================================== */
.creator {
  overflow: hidden;
}
.quick-session {
  padding: 18px 28px 22px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.quick-session-row {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}
.quick-session-row select {
  flex: 0 0 200px;
}
.quick-session-row input {
  flex: 1 1 200px;
  min-width: 0;
}
.quick-session-row .btn-primary {
  flex-shrink: 0;
}

@media (max-width: 560px) {
  .quick-session-row {
    flex-direction: column;
    align-items: stretch;
  }
  .quick-session-row .btn-primary {
    width: 100%;
    justify-content: center;
  }
}

/* ====================================================================
   Wizard modal — 三策略创建向导
   ==================================================================== */
.wizard-modal {
  width: 560px;
}
.wizard-body {
  gap: 16px;
}
.wizard-steps {
  margin-bottom: 4px;
}
.wizard-step {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.wizard-strategy-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}
.wizard-strategy-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  padding: 14px 16px;
  background: var(--surface-soft);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  cursor: pointer;
  text-align: left;
  transition: all var(--dur-fast) var(--ease-out);
}
.wizard-strategy-card:hover {
  border-color: var(--brand);
  background: var(--brand-soft);
}
.wizard-strategy-card.on {
  border-color: var(--brand);
  background: var(--brand-soft);
}
.wizard-strategy-card h4 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
}
.wizard-strategy-card p {
  margin: 0;
  font-size: 11px;
  color: var(--ink-mute);
}
.wizard-strategy-card .strategy-icon {
  font-family: var(--font-mono);
  font-size: 22px;
  color: var(--brand);
  line-height: 1;
}
.wizard-import-zone {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding: 28px 24px;
  border: 2px dashed var(--border);
  border-radius: var(--radius);
  background: var(--surface-soft);
  text-align: center;
  min-height: 160px;
  transition: all var(--dur-fast) var(--ease-out);
}
.wizard-import-zone.is-loading {
  border-color: var(--brand);
  background: var(--surface);
}
.wizard-import-zone .import-icon {
  font-size: 36px;
  line-height: 1;
}
.wizard-import-zone .import-text {
  font-size: 13px;
  color: var(--ink-2);
  margin: 0;
}
.wizard-import-zone .import-spinner {
  width: 36px;
  height: 36px;
  border: 3px solid var(--border);
  border-top-color: var(--brand);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
.wizard-import-done {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 18px;
  border: 1px solid var(--good, #16A34A);
  border-radius: var(--radius);
  background: var(--good-soft, rgba(22, 163, 74, 0.08));
}
.wizard-import-tick {
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--good, #16A34A);
  color: #fff;
  font-weight: 700;
  flex-shrink: 0;
}
.wizard-import-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
  min-width: 0;
}
.wizard-import-label {
  font-size: 11px;
  color: var(--ink-mute);
  letter-spacing: 0.04em;
}
.wizard-import-repo {
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--ink);
  font-weight: 600;
}
.wizard-label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: var(--ink-2);
  font-weight: 500;
}
.wizard-label-half {
  flex: 1 1 0;
  min-width: 0;
}
.wizard-row, .wizard-auth {
  display: flex;
  gap: 12px;
}
.wizard-auth {
  margin-top: 4px;
}
.wizard-hint {
  margin: 0;
  font-size: 12px;
  color: var(--ink-mute);
  line-height: 1.6;
}
.wizard-hint-dim {
  color: var(--ink-faint);
}
.wizard-divider {
  height: 1px;
  background: var(--border);
  margin: 4px 0;
}
.wizard-error {
  background: rgba(217, 45, 32, 0.08);
  border: 1px solid rgba(217, 45, 32, 0.25);
  color: #d92d20;
  padding: 8px 12px;
  border-radius: var(--radius);
  font-size: 12px;
  line-height: 1.5;
}
.wizard-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}
.wizard-step-loading {
  align-items: center;
  text-align: center;
  padding: 24px 0;
}

@media (max-width: 640px) {
  .wizard-modal {
    width: 100vw;
    max-width: 100vw;
  }
  .wizard-strategy-grid {
    grid-template-columns: 1fr;
  }
  .wizard-row {
    flex-direction: column;
  }
}

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

/* ====================================================================
   Entry card — 水平 flex 三段式：左主信息 / 右操作
   ==================================================================== */
.entry {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 18px 22px;
  cursor: pointer;
  animation: fade-up var(--dur-slow) var(--ease-out) both;
  transition: background var(--dur-fast) var(--ease-out), border-color var(--dur-fast) var(--ease-out);
}
.entry:hover { background: var(--surface-hover); }
.entry.is-selected { background: var(--brand-soft); border-color: var(--brand-soft-2); }
.entry.is-selected:hover { background: var(--brand-soft-2); }
.entry.is-deleting { opacity: 0.4; pointer-events: none; }

/* Checkbox 固定 24px 槽位（活跃行也保留占位，align 不抖） */
.entry-check {
  flex: 0 0 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  user-select: none;
}
.entry-check input {
  width: 16px;
  height: 16px;
  margin: 0;
  cursor: pointer;
  accent-color: var(--brand);
}

/* 主信息区：编号 + 状态 | 标题 + meta —— 用 flex 把两组拉开 */
.entry-main {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 18px;
}
.entry-id {
  flex: 0 0 132px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.entry-num {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--ink-faint);
  letter-spacing: 0.04em;
}
.status-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border-radius: var(--radius-pill);
  font-size: 11px;
  font-weight: 500;
  align-self: flex-start;
}
.status-tag.active { background: var(--good-soft); color: var(--good); }
.status-tag.archived { background: var(--surface-soft); color: var(--ink-mute); border: 1px solid var(--border); }
.status-dot { width: 5px; height: 5px; border-radius: 50%; background: currentColor; }

/* 主体：标题 + 单行 meta，meta 用 · 分隔 */
.entry-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.entry-title {
  font-family: var(--font-display);
  font-size: 18px;
  font-weight: 600;
  margin: 0;
  color: var(--ink);
  line-height: 1.3;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.entry-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0;
  font-size: 12px;
  color: var(--ink-mute);
  font-family: var(--font-mono);
}
.meta { display: inline-flex; align-items: baseline; gap: 6px; }
.meta + .meta::before {
  content: '·';
  margin: 0 10px;
  color: var(--ink-faint);
}
.meta .serial { font-size: 10px; letter-spacing: 0.06em; }
.meta code, .meta .mono { font-family: var(--font-mono); color: var(--ink-2); font-size: 11px; }

/* 操作区：横向 + flex-wrap，hover/focus 不挤变形 */
.entry-act {
  flex: 0 0 auto;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  justify-content: flex-end;
}
.entry-act .btn-ghost { padding: 5px 14px; font-size: 12px; gap: 4px; }

/* 批量操作条 */
.batch-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 18px;
  background: linear-gradient(90deg, var(--brand-soft) 0%, transparent 100%);
  border: 1px solid var(--brand-soft-2);
  border-radius: var(--radius-sm);
  font-size: 12px;
  color: var(--ink-2);
}
.batch-count strong { color: var(--brand); font-weight: 600; }
.batch-sep { color: var(--ink-faint); }
.batch-hint { color: var(--ink-mute); font-size: 11px; }
.batch-spacer { flex: 1; }

.batchbar-enter-active, .batchbar-leave-active {
  transition: max-height 0.22s ease, opacity 0.18s ease, transform 0.18s ease;
  overflow: hidden;
}
.batchbar-enter-from, .batchbar-leave-to {
  max-height: 0;
  opacity: 0;
  transform: translateY(-6px);
}
.batchbar-enter-to, .batchbar-leave-from {
  max-height: 60px;
  opacity: 1;
  transform: translateY(0);
}

/* 归档行内"删除"按钮：ghost 风格（不抢眼） */
.btn-mini-danger--ghost {
  background: transparent;
  color: var(--ink-mute);
  border-color: var(--border);
}
.btn-mini-danger--ghost:hover:not(:disabled) {
  background: var(--surface-hover);
  color: #d92d20;
  border-color: #d92d20;
}
.entry-l {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-start;
  flex: 0 0 132px;
}
.entry-num {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--ink-faint);
  letter-spacing: 0.04em;
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

.entry-body { min-width: 0; flex: 1; }
.entry-title {
  font-family: var(--font-display);
  font-size: 18px;
  font-weight: 600;
  margin: 0 0 6px;
  color: var(--ink);
}
.entry-meta { display: flex; flex-wrap: wrap; gap: 0; font-size: 12px; color: var(--ink-mute); }
.meta { display: inline-flex; align-items: baseline; gap: 6px; }
.meta + .meta::before { content: '·'; margin: 0 10px; color: var(--ink-faint); }
.meta .serial { font-size: 10px; letter-spacing: 0.06em; }
.meta code, .meta .mono { font-family: var(--font-mono); color: var(--ink-2); font-size: 11px; }

.entry-act { flex: 0 0 auto; }
.entry-act .btn-ghost { padding: 5px 14px; font-size: 12px; gap: 4px; }

/* ====================================================================
   Mobile
   ==================================================================== */
@media (max-width: 900px) {
  .page { padding: 16px 16px 48px; }
  .creator-card + .creator-card { border-left: 0; border-top: 1px solid var(--border); }
  .creator-card { flex-basis: 100%; }
  .entry { flex-wrap: wrap; padding: 16px 18px; gap: 14px; }
  .entry-main { flex-basis: 100%; min-width: 0; flex-direction: column; align-items: flex-start; gap: 8px; }
  .entry-l { flex-direction: row; flex: 0 0 auto; align-items: center; gap: 10px; }
  .entry-act { flex-direction: row; flex-wrap: wrap; }
}

/* ====================================================================
   Mobile — max-width 768px
   ==================================================================== */
@media (max-width: 768px) {
  .page { padding: 12px 12px 64px; gap: 16px; }

  /* --- meta strip --- */
  .meta-strip { padding: 10px 12px; gap: 8px; font-size: 10px; }
  .meta-val { font-size: 12px; }
  .archive-toggle { font-size: 10px; padding: 3px 8px; }

  /* --- filter strip --- */
  .filter-strip { gap: 8px; }
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

  /* meta 在窄屏塌成纵向键值对 */
  .entry-meta {
    flex-direction: column;
    align-items: flex-start;
    gap: 4px;
  }
  /* 隐藏 · 分隔符（已经被 label 替代） */
  .meta + .meta::before { content: none; margin: 0; }
  /* 每项前注入 data-label 标签：值 */
  .entry-meta .meta {
    display: flex;
    align-items: baseline;
    gap: 8px;
  }
  .entry-meta .meta::before {
    content: attr(data-label);
    flex: 0 0 60px;
    font-size: 10px;
    color: var(--ink-faint);
    letter-spacing: 0.04em;
    text-transform: uppercase;
  }
  .entry-meta .meta .serial { display: none; }  /* 标签已通过 ::before 注入 */
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
  .meta-strip { font-size: 9px; padding: 8px 10px; gap: 6px; }
  .meta-cell { gap: 4px; }
  .meta-val { font-size: 11px; }
  .archive-toggle { font-size: 9px; padding: 3px 6px; }
  .entry-title { font-size: 15px; }
  .entry-meta { gap: 6px; font-size: 11px; }
}
</style>