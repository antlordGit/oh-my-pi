<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useMessage, useDialog } from 'naive-ui'
import { api } from '@/api/http'
import { unarchive as unarchiveSession } from '@/api/session'

const router = useRouter()
const auth = useAuthStore()
const msg = useMessage()
const dialog = useDialog()

const tab = ref<'config' | 'sessions' | 'audit'>('config')
const config = ref<Record<string, any>>({})
const sessions = ref<any[]>([])
const prompts = ref<any[]>([])
const tools = ref<any[]>([])
const responses = ref<any[]>([])
const collapsedGroups = ref(new Set<string>())

/** Auto-collapse new groups when audit data changes. */
function autoCollapse() {
  const s = new Set(collapsedGroups.value)
  for (const g of auditGroups.value) {
    s.add(g.sessionId)
  }
  collapsedGroups.value = s
}

function toggleGroup(sid: string) {
  const s = new Set(collapsedGroups.value)
  if (s.has(sid)) s.delete(sid); else s.add(sid)
  collapsedGroups.value = s
}
const pool = ref<any>(null)
const newKey = ref('')
const newValue = ref('')
const editingKey = ref<string | null>(null)
const editingValue = ref('')

const configCount = computed(() => Object.keys(config.value || {}).length)
// Prefer live process state; falls back to DB status when no live client.
const activeSessions = computed(() =>
  sessions.value.filter(s => (s.effectiveStatus ?? s.status) === 'active').length
)
const poolUsed = computed(() => pool.value?.usedSlots ?? 0)

/** Per-row display status — live process takes precedence over persisted DB status. */
function effectiveStatus(s: any): 'active' | 'archived' {
  return (s.effectiveStatus ?? s.status) === 'active' ? 'active' : 'archived'
}

async function loadConfig() { config.value = (await api.get('/admin/config')).data }

// Persistent optimistic status — survives page reload so an admin who kills a
// session and refreshes the page still sees "archived" until the server-side
// state catches up (kill is async on the backend). Keyed by sessionId, value
// is the operator-confirmed status: 'active' | 'archived'.
const OPTS_KEY = 'omp.admin.sessionOverrides'
function loadOverrides(): Record<string, 'active' | 'archived'> {
  try { return JSON.parse(sessionStorage.getItem(OPTS_KEY) || '{}') } catch { return {} }
}
function saveOverrides(map: Record<string, 'active' | 'archived'>) {
  try { sessionStorage.setItem(OPTS_KEY, JSON.stringify(map)) } catch {}
}
function setOverride(id: string, status: 'active' | 'archived') {
  const m = loadOverrides(); m[id] = status; saveOverrides(m)
}
function clearOverride(id: string) {
  const m = loadOverrides(); delete m[id]; saveOverrides(m)
}

async function loadSessions() {
  const fresh: any[] = (await api.get('/admin/sessions')).data
  // Newest first: sort by lastActiveAt descending so freshly created/updated
  // sessions bubble to the top of the admin table.
  fresh.sort((a, b) => {
    const ta = a.lastActiveAt ? Date.parse(a.lastActiveAt) : 0
    const tb = b.lastActiveAt ? Date.parse(b.lastActiveAt) : 0
    return tb - ta
  })
  // Apply operator overrides so killed/restored rows stick across reloads.
  const overrides = loadOverrides()
  for (const f of fresh) {
    const ov = overrides[f.sessionId]
    if (ov) f.effectiveStatus = ov
    else {
      // Fallback to in-memory optimistic update (covers same-tab refresh).
      const prev = sessions.value.find(s => s.sessionId === f.sessionId)
      if (prev && prev.effectiveStatus) f.effectiveStatus = prev.effectiveStatus
    }
  }
  sessions.value = fresh
  pool.value = (await api.get('/admin/pool')).data
}
async function loadAudit() {
  const [p, t, r] = await Promise.all([
    api.get('/admin/audit/prompts'),
    api.get('/admin/audit/tools'),
    api.get('/admin/audit/responses'),
  ])
  prompts.value = p.data
  tools.value = t.data
  responses.value = r.data
  // Collapse all groups by default; admin clicks to expand.
  autoCollapse()
}
async function loadAll() { await Promise.all([loadConfig(), loadSessions(), loadAudit()]) }

/** Group prompts + tools + responses by sessionId, each group sorted by time descending. */
const auditGroups = computed(() => {
  const map = new Map<string, { sessionId: string; items: any[]; lastTime: number }>()
  const group = (sid: string) => {
    let g = map.get(sid)
    if (!g) { g = { sessionId: sid, items: [], lastTime: 0 }; map.set(sid, g) }
    return g
  }
  for (const p of prompts.value) {
    const g = group(p.sessionId)
    g.items.push({ ...p, _type: 'prompt' as const, _time: p.sentAt })
  }
  for (const t of tools.value) {
    const g = group(t.sessionId)
    g.items.push({ ...t, _type: 'tool' as const, _time: t.startedAt })
  }
  for (const r of responses.value) {
    const g = group(r.sessionId)
    g.items.push({ ...r, _type: 'response' as const, _time: r.finishedAt })
  }
  const groups: { sessionId: string; items: any[]; lastTime: number }[] = []
  for (const g of map.values()) {
    g.items.sort((a: any, b: any) => {
      const ta = a._time ? Date.parse(a._time) : 0
      const tb = b._time ? Date.parse(b._time) : 0
      return tb - ta
    })
    g.lastTime = Math.max(...g.items.map((i: any) => (i._time ? Date.parse(i._time) : 0)))
    groups.push({ sessionId: g.sessionId, items: g.items, lastTime: g.lastTime })
  }
  groups.sort((a, b) => b.lastTime - a.lastTime)
  return groups
})

async function saveConfig() {
  if (!newKey.value) return
  let parsed: any
  try { parsed = JSON.parse(newValue.value) } catch { parsed = newValue.value }
  try {
    await api.put('/admin/config/' + encodeURIComponent(newKey.value), { value: parsed })
    newKey.value = ''; newValue.value = ''
    msg.success('已写入'); await loadConfig()
  } catch (e: any) { msg.error(e?.response?.data?.error || '写入失败') }
}

async function startEdit(key: string) {
  editingKey.value = key
  editingValue.value = pretty(config.value[key])
}

async function saveEdit() {
  if (!editingKey.value) return
  let parsed: any
  try { parsed = JSON.parse(editingValue.value) } catch { parsed = editingValue.value }
  try {
    await api.put('/admin/config/' + encodeURIComponent(editingKey.value), { value: parsed })
    editingKey.value = null; editingValue.value = ''
    msg.success('已保存'); await loadConfig()
  } catch (e: any) { msg.error(e?.response?.data?.error || '保存失败') }
}

function cancelEdit() {
  editingKey.value = null; editingValue.value = ''
}

async function deleteConfig(key: string) { await api.delete('/admin/config/' + encodeURIComponent(key)); msg.success(`${key} 已删除`); await loadConfig() }

/**
 * Force-kill a session: optimistically flip the row to "archived" so the UI feels instant,
 * then fire the request. On failure, roll the row back and surface an error.
 * A background loadSessions() reconciles with server state (covers idle-evict, etc.).
 */
async function killSession(id: string) {
  const row = sessions.value.find(s => s.sessionId === id)
  if (row) row.effectiveStatus = 'archived'
  setOverride(id, 'archived')
  try {
    await api.post('/admin/sessions/' + id + '/kill')
    msg.success('已终止')
  } catch (e: any) {
    if (row) row.effectiveStatus = 'active'
    clearOverride(id)
    msg.error(e?.response?.data?.error || '终止失败')
    return
  }
  // Reload from server to sync real state; the persisted override keeps the
  // row archived even when the backend hasn't finished archiving yet.
  loadSessions()
}

/** Confirm before force-killing a single session. */
function confirmKillSession(id: string) {
  const row = sessions.value.find(s => s.sessionId === id)
  const label = row?.title || id.slice(0, 8)
  dialog.warning({
    title: '终止会话',
    content: `确认终止会话「${label}」？运行中的进程将被强制结束。`,
    positiveText: '终止',
    negativeText: '取消',
    onPositiveClick: () => { void killSession(id) },
  })
}

/** Confirm before killing every currently-active session. */
function confirmKillAllSessions() {
  const ids = sessions.value
    .filter(s => effectiveStatus(s) === 'active')
    .map(s => s.sessionId)
  if (!ids.length) { msg.info('没有活跃会话'); return }
  dialog.error({
    title: '终止所有会话',
    content: `确认终止全部 ${ids.length} 个活跃会话？此操作会强制结束它们的进程，不可撤销。`,
    positiveText: `全部终止 (${ids.length})`,
    negativeText: '取消',
    onPositiveClick: async () => {
      // killSession surfaces its own per-row error/rollback; await all then reconcile.
      await Promise.allSettled(ids.map(id => killSession(id)))
      loadSessions()
    },
  })
}

/**
 * Restore a killed/archived session: optimistic flip to "active", then call the
 * user-facing unarchive API. The next prompt to the session will lazily spawn a
 * new OmpRpcClient (processAlive will reflect that on the next refresh).
 */
async function restoreSession(id: string) {
  const row = sessions.value.find(s => s.sessionId === id)
  if (row) row.effectiveStatus = 'active'
  setOverride(id, 'active')
  try {
    await unarchiveSession(id)
    msg.success('已恢复')
  } catch (e: any) {
    if (row) row.effectiveStatus = 'archived'
    clearOverride(id)
    msg.error(e?.response?.data?.error || '恢复失败')
    return
  }
  loadSessions()
}

/** Confirm before restoring a single session. */
function confirmRestoreSession(id: string) {
  const row = sessions.value.find(s => s.sessionId === id)
  const label = row?.title || id.slice(0, 8)
  dialog.info({
    title: '恢复会话',
    content: `确认恢复会话「${label}」？下次访问时将重新拉起进程。`,
    positiveText: '恢复',
    negativeText: '取消',
    onPositiveClick: () => { void restoreSession(id) },
  })
}

function fmtDate(s?: string) {
  if (!s) return '—'
  const d = new Date(s)
  return `${String(d.getMonth() + 1).padStart(2, '0')}.${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
function pretty(v: any): string { if (v == null) return '—'; if (typeof v === 'string') return v; return JSON.stringify(v, null, 2) }

function logout() { auth.logout(); router.replace('/login') }

onMounted(loadAll)
</script>

<template>
  <div class="page">
    <!-- Topbar — identical to SessionListView -->
    <header class="topbar fade-up">
      <div class="brand">
        <span class="brand-mark">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none">
            <path d="M4 20 L12 4 L20 20 L16 20 L12 12 L8 20 Z" fill="#165DFF"/>
          </svg>
        </span>
        <span class="brand-name">管理</span>
      </div>

      <div class="nav-search">
        <span class="search-icon">⌕</span>
        <input class="search-input" placeholder="搜索配置、会话、审计…" />
      </div>

      <div class="nav-actions">
        <span class="nav-user">
          <span class="serial">{{ auth.username }}</span>
          <span class="tag" style="margin-left:8px">管理员</span>
        </span>
        <button class="btn-ghost" @click="router.push('/sessions')">工作台</button>
        <button class="btn-ghost" @click="logout">登出</button>
        <button class="btn-primary" @click="loadAll">刷新</button>
      </div>
    </header>

    <!-- Admin stats -->
    <section class="admin-hero-stats-section fade-up" style="animation-delay:160ms">
      <div class="admin-hero-stats">
        <div class="stat-cell stat-config">
          <div class="stat-ring">
            <span class="big-num">{{ String(configCount).padStart(2, '0') }}</span>
          </div>
          <div class="stat-foot">
            <span class="serial">运行时配置</span>
          </div>
        </div>
        <div class="stat-cell stat-active">
          <div class="stat-ring">
            <span class="stat-glow active"></span>
            <span class="big-num">{{ String(activeSessions).padStart(2, '0') }}</span>
          </div>
          <div class="stat-foot">
            <span class="stat-dot live"></span>
            <span class="serial">活跃会话</span>
          </div>
        </div>
        <div class="stat-cell stat-pool">
          <div class="stat-ring">
            <span class="big-num">{{ poolUsed }}<span class="big-suffix">/{{ pool?.maxConcurrent || 10 }}</span></span>
          </div>
          <div class="stat-foot">
            <span class="serial">沙箱并发</span>
          </div>
        </div>
        <div class="stat-cell stat-audit">
          <div class="stat-ring">
            <span class="big-num">{{ String(auditGroups.flatMap(g => g.items).length).padStart(3, '0') }}</span>
          </div>
          <div class="stat-foot">
            <span class="serial">审计日志</span>
          </div>
        </div>
      </div>
    </section>

    <!-- Tabs -->
    <nav class="tabs fade-up" style="animation-delay:280ms">
      <button class="tab" :class="{ active: tab === 'config' }" @click="tab = 'config'">
        <span class="tab-num mono">01</span>
        <span>运行时配置</span>
      </button>
      <button class="tab" :class="{ active: tab === 'sessions' }" @click="tab = 'sessions'">
        <span class="tab-num mono">02</span>
        <span>会话与进程</span>
      </button>
      <button class="tab" :class="{ active: tab === 'audit' }" @click="tab = 'audit'">
        <span class="tab-num mono">03</span>
        <span>审计日志</span>
      </button>
    </nav>

    <!-- ============================ CONFIG ============================ -->
    <section v-if="tab === 'config'" class="panel fade-up" style="animation-delay:340ms">
      <header class="panel-head">
        <div>
          <h2 class="panel-title">运行时配置</h2>
          <p class="panel-sub">改动仅对新进程生效。点击「编辑」可在线修改。</p>
        </div>
        <span class="serial dim">{{ configCount }} 项</span>
      </header>

      <div class="config-grid">
        <article v-for="(v, k) in config" :key="k" class="cfg-card card">
          <div class="cfg-head">
            <code class="mono cfg-key">{{ k }}</code>
            <div class="cfg-actions">
              <button v-if="editingKey !== k" class="btn-ghost btn-xs" @click="startEdit(k as string)">编辑</button>
              <button class="btn-mini-danger" @click="deleteConfig(k as string)">删除</button>
            </div>
          </div>
          <div v-if="editingKey === k" class="cfg-edit-wrap">
            <textarea v-model="editingValue" class="cfg-edit mono field-raw" />
            <div class="cfg-edit-foot">
              <button class="btn-primary" @click="saveEdit">保存</button>
              <button class="btn-ghost" @click="cancelEdit">取消</button>
            </div>
          </div>
          <pre v-else class="cfg-body mono">{{ pretty(v) }}</pre>
        </article>
      </div>

      <div class="card cfg-add">
        <span class="tag">新增配置</span>
        <h3 class="cfg-add-title">写入一条<strong>运行时配置</strong></h3>
        <div class="add-fields">
          <input v-model="newKey" class="field-raw" placeholder="配置键 (如 omp.flags.tools)" />
          <input v-model="newValue" class="field-raw" placeholder="值 (JSON 或字符串)" />
          <button class="btn-primary" :disabled="!newKey" @click="saveConfig">写入</button>
        </div>
      </div>
    </section>

    <!-- ============================ SESSIONS ============================ -->
    <section v-if="tab === 'sessions'" class="panel fade-up" style="animation-delay:340ms">
      <header class="panel-head">
        <div>
          <h2 class="panel-title">会话与进程</h2>
          <p class="panel-sub">所有沙箱会话。可强制终止异常进程。</p>
        </div>
        <div class="panel-head-actions">
          <button
            class="btn-mini-danger"
            :disabled="!activeSessions"
            @click="confirmKillAllSessions"
          >终止所有会话</button>
          <span class="serial">池占用 <strong class="accent">{{ poolUsed }}</strong> / 10</span>
        </div>
      </header>

      <div v-if="!sessions.length" class="empty card">
        <span class="empty-icon">—</span>
        <p class="empty-text">暂无会话</p>
        <span class="serial">当前没有用户开启任何会话</span>
      </div>

      <div v-else class="card table-card">
        <div class="table-head mono">
          <span>#</span><span>会话 ID</span><span>标题</span><span>用户</span><span>仓库</span><span>状态</span><span>更新时间</span><span></span>
        </div>
        <div v-for="(s, idx) in sessions" :key="s.sessionId" class="table-row mono">
          <span class="dim">{{ String(idx + 1).padStart(2, '0') }}</span>
          <code class="accent">{{ s.sessionId?.slice(0, 8) }}</code>
          <span class="table-title dim">{{ s.title || '—' }}</span>
          <span class="dim">{{ s.userId }}</span>
          <span class="dim">{{ s.repoId }}</span>
          <span class="status-tag" :class="effectiveStatus(s)">
            <span class="status-dot"></span>
            {{ effectiveStatus(s) === 'active' ? '活跃' : '归档' }}
          </span>
          <span class="dim">{{ fmtDate(s.lastActiveAt) }}</span>
          <button
            v-if="effectiveStatus(s) === 'active'"
            class="btn-mini-danger"
            @click="confirmKillSession(s.sessionId)"
          >终止</button>
          <button
            v-else
            class="btn-mini-primary"
            @click="confirmRestoreSession(s.sessionId)"
          >恢复</button>
        </div>
      </div>
    </section>

    <!-- ============================ AUDIT ============================ -->
    <section v-if="tab === 'audit'" class="panel fade-up" style="animation-delay:340ms">
      <header class="panel-head">
        <div>
          <h2 class="panel-title">审计日志</h2>
          <p class="panel-sub">指令、工具调用与 OMP 回复，按会话分组展示。</p>
        </div>
        <span class="serial dim">{{ auditGroups.flatMap(g => g.items).length }} 条</span>
      </header>

      <div v-if="!auditGroups.length" class="empty card">
        <span class="empty-icon">—</span>
        <p class="empty-text">暂无审计记录</p>
      </div>
      <div v-else class="audit-groups">
        <div v-for="group in auditGroups" :key="group.sessionId" class="card audit-group-card">
          <header class="audit-group-head" @click="toggleGroup(group.sessionId)" style="cursor:pointer">
            <span class="audit-group-head-left">
              <span class="audit-group-arrow mono" :class="{ open: !collapsedGroups.has(group.sessionId) }">▶</span>
              <code class="accent">{{ group.sessionId?.slice(0, 8) }}</code>
              <span class="dim" style="font-size:11px">{{ group.items.length }} 条</span>
            </span>
            <span class="dim mono" style="font-size:11px">{{ fmtDate(group.lastTime) }}</span>
          </header>
          <div v-if="!collapsedGroups.has(group.sessionId)">
            <div v-for="item in group.items" :key="item._type + item.id" class="audit-row mono">
              <span class="dim">{{ fmtDate(item._time) }}</span>

              <!-- type badge -->
              <span class="audit-type"
                :class="{ prompt: item._type === 'prompt', tool: item._type === 'tool', response: item._type === 'response' }"
              >
                {{ { prompt: '指令', tool: '工具', response: '回复' }[item._type as string] }}
              </span>

              <!-- prompt: plain text -->
              <span v-if="item._type === 'prompt'" class="audit-text">{{ (item.promptText || '').slice(0, 400) }}</span>
              <!-- tool: toolName + isError badge + arguments -->
              <span v-else-if="item._type === 'tool'" class="audit-text dim">
                <span class="status-tag" :class="item.isError ? 'err' : 'ok'" style="margin-right:6px">
                  <span class="status-dot"></span>
                  {{ item.toolName }}{{ item.isError ? ' · 失败' : '' }}
                </span>
                {{ (item.arguments || '').slice(0, 160) }}
              </span>
              <!-- response: stopReason badge + fullText -->
              <span v-else class="audit-text dim">
                <span class="status-tag" :class="item.isError ? 'err' : 'ok'" style="margin-right:6px">
                  <span class="status-dot"></span>
                  {{ item.isError ? '失败' : item.stopReason === 'stop' ? '完成' : (item.stopReason || '完成') }}
                </span>
                {{ (item.fullText || '').slice(0, 400) }}
              </span>
            </div>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.page {
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 32px 80px;
  display: flex;
  flex-direction: column;
  gap: 32px;
}

/* ====================================================================
   Topbar — identical to SessionListView
   ==================================================================== */
.topbar {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 16px 0;
  position: sticky;
  top: 0;
  background: rgba(255, 255, 255, 0.86);
  backdrop-filter: saturate(180%) blur(16px);
  -webkit-backdrop-filter: saturate(180%) blur(16px);
  z-index: 10;
}
.brand { display: inline-flex; align-items: center; gap: 8px; }
.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px; height: 32px;
  background: rgba(22, 93, 255, 0.10);
  border-radius: 8px;
}
.brand-name { font-size: 18px; font-weight: 700; letter-spacing: -0.01em; }

.nav-search {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
  background: var(--surface-soft);
  margin-left: 24px;
  transition: border-color var(--dur-fast) var(--ease-out);
}
.nav-search:focus-within { border-color: var(--brand); background: var(--surface); }
.search-icon { color: var(--ink-mute); font-size: 14px; }
.search-input {
  border: 0;
  background: transparent;
  outline: 0;
  font-size: 13px;
  width: 240px;
  color: var(--ink);
}
.search-input::placeholder { color: var(--ink-mute); }

.nav-actions { margin-left: auto; display: inline-flex; align-items: center; gap: 12px; }
.nav-user { display: inline-flex; align-items: center; }
.nav-user .serial { color: var(--ink-2); }

/* ====================================================================
   Admin Hero stats
   ==================================================================== */
/* ====================================================================
   Admin Hero stats — instrumentation panel (same language as SessionListView)
   ==================================================================== */
.admin-hero-stats-section {
  padding: 24px 0 8px;
  position: relative;
  z-index: 1;
}

.admin-hero-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 1px;
  background: var(--border);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
}

.admin-hero-stats .stat-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  padding: 28px 16px 22px;
  background: var(--surface);
  position: relative;
  border: 0;
  border-radius: 0;
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

@keyframes stat-pulse {
  0%, 100% { opacity: 0.6; }
  50% { opacity: 1; }
}

.admin-hero-stats .big-num {
  font-family: var(--font-mono);
  font-size: clamp(36px, 3.6vw, 48px);
  font-weight: 700;
  letter-spacing: -0.04em;
  color: var(--brand);
  line-height: 1;
  position: relative;
  z-index: 1;
  font-variant-numeric: tabular-nums;
}

/* Active — green */
.stat-active .big-num {
  color: #16A34A;
}

.big-suffix {
  font-size: 16px;
  font-weight: 600;
  color: var(--ink-mute);
  margin-left: 2px;
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

/* ====================================================================
   Mobile collapse
   ==================================================================== */
@media (max-width: 900px) {
  .admin-hero-stats {
    grid-template-columns: repeat(2, 1fr);
  }
  .admin-hero-stats .stat-cell {
    padding: 20px 14px 18px;
    gap: 10px;
  }
  .stat-ring {
    width: 72px;
    height: 72px;
  }
  .admin-hero-stats .big-num {
    font-size: 32px;
  }
}

/* ====================================================================
   Tabs
   ==================================================================== */
.tabs {
  display: flex;
  gap: 0;
  border-bottom: 1px solid var(--border);
}
.tab {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  background: transparent;
  border: 0;
  padding: 12px 20px;
  font-size: 14px;
  font-weight: 500;
  color: var(--ink-mute);
  cursor: pointer;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  transition: color var(--dur-fast) var(--ease-out), border-color var(--dur-fast) var(--ease-out);
}
.tab:hover { color: var(--brand); }
.tab.active {
  color: var(--brand);
  border-bottom-color: var(--brand);
  font-weight: 600;
}
.tab-num {
  font-size: 11px;
  background: var(--surface-soft);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 1px 6px;
}
.tab.active .tab-num {
  background: var(--brand-soft);
  border-color: var(--brand-soft-2);
  color: var(--brand);
  font-weight: 600;
}

/* ====================================================================
   Panel
   ==================================================================== */
.panel { display: flex; flex-direction: column; gap: 16px; }
.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 16px;
  flex-wrap: wrap;
}
.panel-head-actions {
  display: inline-flex;
  align-items: center;
  gap: 14px;
}
.panel-title {
  font-family: var(--font-display);
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.02em;
  margin: 0;
}
.panel-sub { font-size: 13px; color: var(--ink-mute); margin: 4px 0 0; }
.block-title {
  font-family: var(--font-display);
  font-size: 16px;
  font-weight: 600;
  margin: 8px 0 -4px;
  color: var(--ink);
}
.accent { color: var(--brand); font-weight: 600; }
.dim { color: var(--ink-mute); }

/* Empty state */
.empty {
  padding: 56px 24px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}
.empty-icon { font-size: 48px; color: var(--ink-faint); }
.empty-text { font-size: 16px; font-weight: 600; color: var(--ink-2); margin: 4px 0; }

/* ====================================================================
   Config grid
   ==================================================================== */
.config-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
  gap: 12px;
}
.cfg-card { overflow: hidden; }
.cfg-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 18px;
  border-bottom: 1px solid var(--border);
  background: var(--surface-soft);
}
.cfg-key {
  font-size: 12px;
  color: var(--brand);
  font-weight: 600;
}
.cfg-actions { display: flex; gap: 6px; }
.btn-xs { padding: 4px 12px; font-size: 11px; }

.cfg-body {
  margin: 0;
  padding: 14px 18px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink-2);
  max-height: 200px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
.cfg-edit-wrap { display: flex; flex-direction: column; }
.cfg-edit {
  border-radius: 0 !important;
  border-left: 0 !important;
  border-right: 0 !important;
  border-bottom: 0 !important;
  min-height: 150px;
  background: var(--surface) !important;
  font-size: 12px !important;
}
.cfg-edit:focus { box-shadow: none !important; }
.cfg-edit-foot {
  display: flex;
  gap: 8px;
  padding: 12px 18px;
  border-top: 1px solid var(--border);
  background: var(--surface-soft);
}

.cfg-add { padding: 24px; display: flex; flex-direction: column; gap: 12px; }
.cfg-add-title {
  font-family: var(--font-display);
  font-size: 18px;
  font-weight: 600;
  margin: 0;
}
.cfg-add-title strong { color: var(--brand); font-weight: 700; }

.add-fields { display: grid; grid-template-columns: 1fr 2fr auto; gap: 10px; align-items: center; }

/* ====================================================================
   Table (sessions)
   ==================================================================== */
.table-card { overflow: hidden; padding: 0; }
.table-head, .table-row {
  display: grid;
  grid-template-columns: 40px 100px 1fr 80px 100px 90px 130px 80px;
  gap: 14px;
  align-items: center;
  padding: 12px 18px;
  font-size: 12px;
}
.table-head {
  border-bottom: 1px solid var(--border);
  background: var(--surface-soft);
  font-size: 10px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--ink-mute);
}
.table-row {
  border-bottom: 1px solid var(--border);
  color: var(--ink-2);
  transition: background var(--dur-fast) var(--ease-out);
}
.table-row:last-child { border-bottom: 0; }
.table-row:hover { background: var(--surface-hover); }

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
.status-tag.ok { background: var(--good-soft); color: var(--good); }
.status-tag.err { background: var(--danger-soft); color: var(--danger); }
.status-dot { width: 5px; height: 5px; border-radius: 50%; background: currentColor; }

.btn-mini-primary {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 12px;
  border-radius: var(--radius-pill);
  border: 1px solid var(--brand-soft-2);
  background: var(--brand-soft);
  color: var(--brand);
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease-out), border-color var(--dur-fast) var(--ease-out);
}
.btn-mini-primary:hover { background: var(--brand); color: #fff; border-color: var(--brand); }

/* ====================================================================
   Audit
   ==================================================================== */
.audit-card { overflow: hidden; padding: 0; }
.audit-groups { display: flex; flex-direction: column; gap: 16px; }
.audit-group-card { overflow: hidden; padding: 0; }
.audit-group-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 18px;
  border-bottom: 1px solid var(--border);
  background: var(--surface-soft);
  font-size: 13px;
  user-select: none;
  transition: background var(--dur-fast) var(--ease-out);
}
.audit-group-head:hover { background: var(--surface-hover); }
.audit-group-head-left { display: inline-flex; align-items: center; gap: 8px; }
.audit-group-arrow {
  font-size: 9px;
  color: var(--ink-mute);
  transition: transform var(--dur-fast) var(--ease-out);
  display: inline-block;
}
.audit-group-arrow.open { transform: rotate(90deg); }
.audit-row {
  display: grid;
  grid-template-columns: 90px 80px 1fr;
  gap: 14px;
  padding: 10px 18px;
  border-bottom: 1px solid var(--border);
  font-size: 12px;
  color: var(--ink-2);
  align-items: baseline;
  transition: background var(--dur-fast) var(--ease-out);
}
.audit-row:last-child { border-bottom: 0; }
.audit-row:hover { background: var(--surface-hover); }
.audit-text { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.audit-type {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: var(--radius-pill);
  font-size: 11px;
  font-weight: 500;
  background: var(--surface-soft);
  border: 1px solid var(--border);
  color: var(--ink-mute);
}
.audit-type.prompt { background: var(--brand-soft); border-color: var(--brand-soft-2); color: var(--brand); }
.audit-type.tool   { background: var(--good-soft); border-color: var(--good-soft-2, var(--good-soft)); color: var(--good); }
.audit-type.response { background: var(--surface-soft); border-color: var(--border); color: var(--warning, var(--ink-2)); }

/* ====================================================================
   Mobile
   ==================================================================== */
@media (max-width: 960px) {
  .page { padding: 0 16px 48px; }
  .topbar { flex-wrap: wrap; gap: 12px; }
  .nav-search { display: none; }
  .admin-hero-stats-section { padding: 16px 0 8px; }
  .config-grid { grid-template-columns: 1fr; }
  .add-fields { grid-template-columns: 1fr; }
  .table-head, .table-row { grid-template-columns: 30px 80px 1fr 70px 90px; font-size: 11px; }
  .table-head span:nth-child(3),
  .table-head span:nth-child(6),
  .table-row > :nth-child(3),
  .table-row > :nth-child(6) { display: none; }
  .audit-row { grid-template-columns: 80px 1fr; }
  .audit-row > :nth-child(2) { display: none; }  /* hide type badge */
}
</style>