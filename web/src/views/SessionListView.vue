<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useMessage } from 'naive-ui'
import { listSessions, createSession, archive, type SessionSummary } from '@/api/session'
import { listRepos, createRepo, type Repo } from '@/api/repo'

const router = useRouter()
const auth = useAuthStore()
const msg = useMessage()

const sessions = ref<SessionSummary[]>([])
const repos = ref<Repo[]>([])
const loading = ref(false)

const newRepoId = ref('')
const newRepoName = ref('')
const newSessionRepo = ref<string>('')
const newSessionTitle = ref('')
const showCreator = ref(false)

const activeCount = computed(() => sessions.value.filter(s => s.status === 'active').length)
const archivedCount = computed(() => sessions.value.filter(s => s.status === 'archived').length)

async function refresh() {
  loading.value = true
  try {
    sessions.value = await listSessions()
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
    <!-- Masthead -->
    <header class="masthead fade-up">
      <div class="masthead-row">
        <div class="brand">
          <span class="brand-mark">OMP</span>
          <span class="brand-sub serif">Studio</span>
        </div>
        <div class="masthead-actions">
          <span class="serial">{{ auth.username }}</span>
          <span v-if="auth.isAdmin" class="admin-badge">管理员</span>
          <button v-if="auth.isAdmin" class="lnk" @click="router.push('/admin')">控制室</button>
          <button class="lnk" @click="logout">登出</button>
        </div>
      </div>
      <h1 class="title serif">
        会话列表
        <em>.</em>
      </h1>
    </header>

    <!-- Stats bar -->
    <div class="stats-bar fade-up" style="animation-delay:80ms">
      <div class="stat">
        <span class="stat-num serif">{{ String(activeCount).padStart(2, '0') }}</span>
        <span class="stat-label serial">活跃</span>
      </div>
      <div class="stat-div"></div>
      <div class="stat">
        <span class="stat-num serif">{{ String(archivedCount).padStart(2, '0') }}</span>
        <span class="stat-label serial">已归档</span>
      </div>
      <div class="stat-div"></div>
      <div class="stat">
        <span class="stat-num serif">{{ String(repos.length).padStart(2, '0') }}</span>
        <span class="stat-label serial">仓库</span>
      </div>
      <div class="stat-spacer"></div>
      <button class="btn-primary" @click="showCreator = !showCreator">
        {{ showCreator ? '收起' : '+ 新建会话' }}
      </button>
    </div>

    <!-- Creator panel -->
    <transition name="slide">
      <div v-if="showCreator" class="creator">
        <div class="creator-grid">
          <div class="surface" style="padding:28px">
            <div class="creator-label serial">01 · 登记仓库</div>
            <input v-model="newRepoId" class="field-raw" placeholder="仓库标识（英文/数字）" style="margin-top:12px" />
            <input v-model="newRepoName" class="field-raw" placeholder="显示名（可选）" style="margin-top:8px" />
            <button class="btn-ghost" :disabled="!newRepoId" @click="onCreateRepo" style="margin-top:12px">登记</button>
          </div>
          <div class="surface" style="padding:28px">
            <div class="creator-label serial">02 · 开启会话</div>
            <select v-model="newSessionRepo" class="field-raw sel" style="margin-top:12px">
              <option v-if="!repos.length" disabled value="">暂无仓库</option>
              <option v-for="r in repos" :key="r.repoId" :value="r.repoId">{{ r.displayName }} · {{ r.repoId }}</option>
            </select>
            <input v-model="newSessionTitle" class="field-raw" placeholder="会话标题（可选）" style="margin-top:8px" />
            <button class="btn-primary" style="width:100%;margin-top:12px" :disabled="!newSessionRepo" @click="onCreateSession">进入 →</button>
          </div>
        </div>
      </div>
    </transition>

    <!-- Session list -->
    <section class="ledger fade-up" style="animation-delay:120ms">
      <div v-if="!sessions.length" class="empty">
        <span class="empty-icon">—</span>
        <p>暂无会话记录，点击上方「新建会话」开始。</p>
      </div>

      <div v-else class="entries">
        <article
          v-for="(s, idx) in sessions"
          :key="s.sessionId"
          class="entry surface"
          :class="{ archived: s.status === 'archived' }"
          :style="{ animationDelay: 120 + idx * 35 + 'ms' }"
          @click="router.push('/sessions/' + s.sessionId)"
        >
          <span class="entry-num serif">{{ String(idx + 1).padStart(2, '0') }}</span>
          <div class="entry-body">
            <div class="entry-top">
              <h3 class="entry-title serif">{{ s.title || '未命名会话' }}</h3>
              <code class="entry-id mono">{{ s.sessionId.slice(0, 8) }}</code>
            </div>
            <div class="entry-meta">
              <span class="meta">仓库 · <code class="mono">{{ s.repoId }}</code></span>
              <span class="meta">更新 · {{ fmtDate(s.lastActiveAt) }}</span>
              <span class="meta" :class="{ active: s.status === 'active' }">
                {{ s.status === 'active' ? '活跃' : '已归档' }}
              </span>
            </div>
          </div>
          <div class="entry-act" @click.stop>
            <button class="btn-ghost" style="padding:6px 12px;font-size:10px" @click="router.push('/sessions/' + s.sessionId)">打开 →</button>
            <button v-if="s.status === 'active'" class="btn-mini-danger" @click="onArchive(s.sessionId)">归档</button>
          </div>
        </article>
      </div>
    </section>
  </div>
</template>

<style scoped>
.page {
  max-width: 880px;
  margin: 0 auto;
  padding: 48px 40px 80px;
}

/* Masthead */
.masthead { margin-bottom: 28px; }
.masthead-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
.brand {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.brand-mark {
  font-family: var(--font-mono);
  font-weight: 700;
  font-size: 13px;
  letter-spacing: 0.3em;
  background: var(--accent);
  color: #fff;
  padding: 4px 12px;
  border-radius: 6px;
}
.brand-sub {
  font-size: 26px;
  font-weight: 300;
  font-style: italic;
  color: var(--text);
}
.masthead-actions {
  display: flex;
  align-items: center;
  gap: 18px;
}
.admin-badge {
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.14em;
  background: var(--accent-soft);
  color: var(--accent);
  padding: 3px 10px;
  border-radius: 6px;
  font-weight: 600;
}
.lnk {
  background: transparent; border: 0;
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--text-muted);
  cursor: pointer;
}
.lnk:hover { color: var(--accent); }

.title {
  font-size: 80px;
  font-weight: 300;
  line-height: 0.94;
  letter-spacing: -0.03em;
  font-variation-settings: "opsz" 144, "SOFT" 30;
}
.title em {
  color: var(--accent);
  font-style: italic;
  font-variation-settings: "opsz" 144, "SOFT" 100, "WONK" 1;
}

/* Stats bar */
.stats-bar {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 12px 0 28px;
}
.stat { display: flex; flex-direction: column; gap: 2px; }
.stat-num {
  font-size: 32px;
  font-weight: 300;
  font-variation-settings: "opsz" 144;
  color: var(--text);
}
.stat-div {
  width: 1px;
  height: 28px;
  background: var(--border);
}
.stat-spacer { flex: 1; }

/* Creator */
.creator { margin-bottom: 24px; }
.creator-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.creator-label {
  color: var(--accent);
  font-weight: 600;
}
.sel { appearance: none; cursor: pointer; }

/* Ledger */
.empty {
  text-align: center;
  padding: 80px 0;
  color: var(--text-muted);
}
.empty-icon {
  font-family: var(--font-display);
  font-size: 64px;
  display: block;
  margin-bottom: 12px;
}

.entries {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.entry {
  display: grid;
  grid-template-columns: 44px 1fr auto;
  gap: 20px;
  align-items: center;
  padding: 20px 28px;
  cursor: pointer;
  transition: all 180ms ease;
}
.entry:hover {
  transform: translateX(4px);
  border-color: var(--accent);
}
.entry.archived { opacity: 0.55; }
.entry.archived:hover { opacity: 0.75; }

.entry-num {
  font-size: 18px;
  font-weight: 300;
  color: var(--text-faint);
  font-variation-settings: "opsz" 144;
}

.entry-top {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 6px;
}
.entry-title {
  font-size: 20px;
  font-weight: 380;
  font-variation-settings: "opsz" 144, "SOFT" 30;
  margin: 0;
}
.entry-id {
  font-size: 10px;
  color: var(--text-muted);
  background: var(--bg-sunken);
  padding: 2px 8px;
  border-radius: 4px;
}
.entry-meta {
  display: flex;
  gap: 22px;
  font-size: 13px;
  color: var(--text-muted);
}
.entry-meta code { font-size: 11px; color: var(--text-secondary); }
.meta.active { color: var(--good); font-weight: 500; }

.entry-act {
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: flex-end;
}

/* Slide transition */
.slide-enter-active, .slide-leave-active {
  transition: all 280ms cubic-bezier(0.2, 0.6, 0.2, 1);
}
.slide-enter-from, .slide-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

@media (max-width: 700px) {
  .page { padding: 32px 20px; }
  .title { font-size: 48px; }
  .creator-grid { grid-template-columns: 1fr; }
  .entry { grid-template-columns: 1fr; padding: 16px 20px; }
  .entry-num { display: none; }
}
</style>
