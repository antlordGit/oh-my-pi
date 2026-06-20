<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useMessage } from 'naive-ui'
import { api } from '@/api/http'
import { unarchive as unarchiveSession } from '@/api/session'

const router = useRouter()
const auth = useAuthStore()
const msg = useMessage()

const tab = ref<'config' | 'sessions' | 'audit'>('config')
const config = ref<Record<string, any>>({})
const sessions = ref<any[]>([])
const prompts = ref<any[]>([])
const tools = ref<any[]>([])
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
async function loadSessions() {
  sessions.value = (await api.get('/admin/sessions')).data
  pool.value = (await api.get('/admin/pool')).data
}
async function loadAudit() {
  prompts.value = (await api.get('/admin/audit/prompts')).data
  tools.value = (await api.get('/admin/audit/tools')).data
}
async function loadAll() { await Promise.all([loadConfig(), loadSessions(), loadAudit()]) }

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
  try {
    await api.post('/admin/sessions/' + id + '/kill')
    msg.success('已终止')
  } catch (e: any) {
    if (row) row.effectiveStatus = 'active'
    msg.error(e?.response?.data?.error || '终止失败')
    return
  }
  // Reconcile with server; do not block the UI on this.
  loadSessions()
}

/**
 * Restore a killed/archived session: optimistic flip to "active", then call the
 * user-facing unarchive API. The next prompt to the session will lazily spawn a
 * new OmpRpcClient (processAlive will reflect that on the next refresh).
 */
async function restoreSession(id: string) {
  const row = sessions.value.find(s => s.sessionId === id)
  if (row) row.effectiveStatus = 'active'
  try {
    await unarchiveSession(id)
    msg.success('已恢复')
  } catch (e: any) {
    if (row) row.effectiveStatus = 'archived'
    msg.error(e?.response?.data?.error || '恢复失败')
    return
  }
  loadSessions()
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
        <span class="brand-name">OMP</span>
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
        <div class="stat-cell">
          <span class="serial">运行时配置</span>
          <span class="big-num">{{ String(configCount).padStart(2, '0') }}</span>
          <span class="stat-foot">已登记的配置项</span>
        </div>
        <div class="stat-cell">
          <span class="serial">活跃会话</span>
          <span class="big-num">{{ String(activeSessions).padStart(2, '0') }}</span>
          <span class="stat-foot">当下正在运行</span>
        </div>
        <div class="stat-cell">
          <span class="serial">池占用</span>
          <span class="big-num">{{ poolUsed }}<span class="big-suffix">/10</span></span>
          <span class="stat-foot">沙箱并发上限</span>
        </div>
        <div class="stat-cell">
          <span class="serial">审计日志</span>
          <span class="big-num">{{ String(prompts.length + tools.length).padStart(3, '0') }}</span>
          <span class="stat-foot">指令 + 工具调用</span>
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
        <span class="serial">池占用 <strong class="accent">{{ poolUsed }}</strong> / 10</span>
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
            @click="killSession(s.sessionId)"
          >终止</button>
          <button
            v-else
            class="btn-mini-primary"
            @click="restoreSession(s.sessionId)"
          >恢复</button>
        </div>
      </div>
    </section>

    <!-- ============================ AUDIT ============================ -->
    <section v-if="tab === 'audit'" class="panel fade-up" style="animation-delay:340ms">
      <header class="panel-head">
        <div>
          <h2 class="panel-title">审计日志</h2>
          <p class="panel-sub">最近 50 条指令与工具调用，按时间倒序。</p>
        </div>
        <span class="serial dim">{{ prompts.length + tools.length }} 条</span>
      </header>

      <h3 class="block-title">指令记录</h3>
      <div v-if="!prompts.length" class="empty card">
        <span class="empty-icon">—</span>
        <p class="empty-text">暂无指令记录</p>
      </div>
      <div v-else class="card audit-card">
        <div v-for="p in prompts" :key="'p' + p.id" class="audit-row mono">
          <span class="dim">{{ fmtDate(p.sentAt) }}</span>
          <code class="accent">{{ p.sessionId?.slice(0, 8) }}</code>
          <span class="audit-text">{{ (p.promptText || '').slice(0, 200) }}</span>
        </div>
      </div>

      <h3 class="block-title">工具调用</h3>
      <div v-if="!tools.length" class="empty card">
        <span class="empty-icon">—</span>
        <p class="empty-text">暂无工具调用</p>
      </div>
      <div v-else class="card audit-card">
        <div v-for="t in tools" :key="'t' + t.id" class="audit-row mono">
          <span class="dim">{{ fmtDate(t.startedAt) }}</span>
          <code class="accent">{{ t.sessionId?.slice(0, 8) }}</code>
          <span class="status-tag" :class="t.isError ? 'err' : 'ok'">
            <span class="status-dot"></span>
            {{ t.toolName }}{{ t.isError ? ' · 失败' : '' }}
          </span>
          <span class="audit-text dim">{{ (t.arguments || '').slice(0, 160) }}</span>
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
.admin-hero-stats-section {
  padding: 32px 0 16px;
  position: relative;
  z-index: 1;
}

.admin-hero-stats {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  background: linear-gradient(135deg,
    rgba(22, 93, 255, 0.04) 0%,
    rgba(123, 123, 255, 0.08) 100%);
  border: 1px solid var(--border-soft);
  border-radius: var(--radius-card);
  padding: 20px;
}
.stat-cell {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 14px;
  background: rgba(255, 255, 255, 0.6);
  border-radius: var(--radius);
  border: 1px solid var(--border);
}
.big-num {
  font-family: var(--font-display);
  font-size: clamp(28px, 2.6vw, 38px);
  font-weight: 800;
  letter-spacing: -0.025em;
  color: var(--brand);
  line-height: 1;
}
.big-suffix {
  font-size: 16px;
  font-weight: 600;
  color: var(--ink-mute);
  margin-left: 2px;
}
.stat-foot { font-size: 11px; color: var(--ink-mute); }

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
.audit-row {
  display: grid;
  grid-template-columns: 90px 70px 160px 1fr;
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
  .audit-row { grid-template-columns: 80px 60px 1fr; }
  .audit-row > :nth-child(3) { display: none; }
}
</style>