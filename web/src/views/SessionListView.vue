<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useMessage } from 'naive-ui'
import { listSessions, createSession, archive, unarchive, type SessionSummary } from '@/api/session'
import { listRepos, createRepo, type Repo } from '@/api/repo'

const router = useRouter()
const auth = useAuthStore()
const msg = useMessage()

const sessions = ref<SessionSummary[]>([])
const repos = ref<Repo[]>([])
const loading = ref(false)
const filter = ref<'all' | 'active' | 'archived'>('all')

const newRepoId = ref('')
const newRepoName = ref('')
const newSessionRepo = ref<string>('')
const newSessionTitle = ref('')
const showCreator = ref(false)

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
    msg.success('仓库已登记')
    await refresh()
  } catch (e: any) { msg.error(e?.response?.data?.error || '登记失败') }
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

async function onArchive(id: string) {
  try { await archive(id); msg.success('已归档'); await refresh() }
  catch (e: any) { msg.error(e?.response?.data?.error || '归档失败') }
}

async function onUnarchive(id: string) {
  try { await unarchive(id); msg.success('已恢复'); await refresh() }
  catch (e: any) { msg.error(e?.response?.data?.error || '恢复失败') }
}

function effectiveStatus(s: SessionSummary): 'active' | 'archived' {
  // Prefer admin-side optimistic override (persisted in sessionStorage) --
  // keyed by full UUID; also match on the first 8 chars of the sessionId
  // so that both views can find the same override entry.
  const key = 'omp.admin.sessionOverrides'
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

function logout() { auth.logout(); router.replace('/login') }
onMounted(refresh)
</script>

<template>
  <div class="page">
    <!-- Topbar (Volcengine split nav) -->
    <header class="topbar fade-up">
      <div class="brand">
        <span class="brand-mark">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none">
            <path d="M4 20 L12 4 L20 20 L16 20 L12 12 L8 20 Z" fill="#165DFF"/>
          </svg>
        </span>
        <span class="brand-name">OMP</span>
      </div>

      <nav class="nav-links">
        <a class="nav-link active">工作台</a>
        <a class="nav-link">模型</a>
        <a class="nav-link">解决方案</a>
        <a class="nav-link">定价</a>
        <a class="nav-link">文档</a>
      </nav>

      <div class="nav-search">
        <span class="search-icon">⌕</span>
        <input class="search-input" placeholder="搜索会话、仓库" />
      </div>

      <div class="nav-actions">
        <span class="nav-user">
          <span class="serial">{{ auth.username }}</span>
          <span v-if="auth.isAdmin" class="tag" style="margin-left:8px">管理员</span>
        </span>
        <button v-if="auth.isAdmin" class="btn-ghost" @click="router.push('/admin')">控制室</button>
        <button class="btn-ghost" @click="logout">登出</button>
        <button class="btn-primary" @click="showCreator = !showCreator">
          {{ showCreator ? '收起' : '新建会话' }}
        </button>
      </div>
    </header>

    <!-- Hero stats -->
    <section class="hero-stats-section fade-up" style="animation-delay:160ms">
      <div class="hero-stats">
        <div class="stat-cell">
          <span class="serial">活跃</span>
          <span class="big-num">{{ String(activeCount).padStart(2, '0') }}</span>
        </div>
        <div class="stat-cell">
          <span class="serial">归档</span>
          <span class="big-num">{{ String(archivedCount).padStart(2, '0') }}</span>
        </div>
        <div class="stat-cell">
          <span class="serial">仓库</span>
          <span class="big-num">{{ String(repos.length).padStart(2, '0') }}</span>
        </div>
        <div class="stat-cell">
          <span class="serial">总数</span>
          <span class="big-num">{{ String(totalSessions).padStart(2, '0') }}</span>
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
    </div>

    <!-- Creator -->
    <transition name="slide-down">
      <section v-if="showCreator" class="creator card">
        <div class="creator-grid">
          <div class="creator-card">
            <span class="tag">01 · 登记仓库</span>
            <h3 class="creator-title">登记一个<br /><strong>新仓库</strong></h3>
            <input v-model="newRepoId" class="field-raw" placeholder="仓库标识（英文/数字）" />
            <input v-model="newRepoName" class="field-raw" placeholder="显示名（可选）" />
            <button class="btn-outline" :disabled="!newRepoId" @click="onCreateRepo">登记</button>
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
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 32px 80px;
  display: flex;
  flex-direction: column;
  gap: 40px;
}

/* ====================================================================
   Topbar
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

.nav-links { display: inline-flex; align-items: center; gap: 24px; }
.nav-link {
  font-size: 14px;
  color: var(--ink-2);
  cursor: pointer;
  transition: color var(--dur-fast) var(--ease-out);
}
.nav-link:hover, .nav-link.active { color: var(--brand); }

.nav-search {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
  background: var(--surface-soft);
  transition: border-color var(--dur-fast) var(--ease-out);
}
.nav-search:focus-within { border-color: var(--brand); background: var(--surface); }
.search-icon { color: var(--ink-mute); font-size: 14px; }
.search-input {
  border: 0;
  background: transparent;
  outline: 0;
  font-size: 13px;
  width: 220px;
  color: var(--ink);
}
.search-input::placeholder { color: var(--ink-mute); }

.nav-actions {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 12px;
}
.nav-user { display: inline-flex; align-items: center; }
.nav-user .serial { color: var(--ink-2); }

/* ====================================================================
   Hero stats
   ==================================================================== */
.hero-stats-section {
  padding: 32px 0 16px;
  position: relative;
  z-index: 1;
}

.hero-stats {
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
  gap: 8px;
  padding: 14px;
  background: rgba(255, 255, 255, 0.6);
  border-radius: var(--radius);
  border: 1px solid var(--border);
}
.big-num {
  font-family: var(--font-display);
  font-size: clamp(32px, 3vw, 44px);
  font-weight: 800;
  letter-spacing: -0.025em;
  color: var(--brand);
  line-height: 1;
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
  .page { padding: 0 16px 48px; }
  .topbar { flex-wrap: wrap; gap: 12px; }
  .nav-links, .nav-search { display: none; }
  .nav-actions { margin-left: 0; width: 100%; justify-content: flex-end; }
  .hero-stats-section { padding: 16px 0 8px; }
  .creator-grid { grid-template-columns: 1fr; }
  .creator-card + .creator-card { border-left: 0; border-top: 1px solid var(--border); }
  .entry { grid-template-columns: 1fr; padding: 16px 18px; }
  .entry-act { flex-direction: row; }
}
</style>