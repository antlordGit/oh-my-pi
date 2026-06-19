<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import { api } from '@/api/http'

const router = useRouter()
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
async function killSession(id: string) { await api.post('/admin/sessions/' + id + '/kill'); msg.success('已终止'); await loadSessions() }

function fmtDate(s?: string) {
  if (!s) return '—'
  const d = new Date(s)
  return `${String(d.getMonth() + 1).padStart(2, '0')}.${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
function pretty(v: any): string { if (v == null) return '—'; if (typeof v === 'string') return v; return JSON.stringify(v, null, 2) }

onMounted(loadAll)
</script>

<template>
  <div class="admin">
    <header class="masthead fade-up">
      <div class="masthead-row">
        <button class="btn-ghost" style="padding:5px 12px;font-size:10px" @click="router.push('/sessions')">← 工作台</button>
        <span class="serial dim">控制室</span>
      </div>
      <h1 class="title serif">管理面板<em>.</em></h1>
    </header>

    <nav class="tabs fade-up" style="animation-delay:60ms">
      <button class="tab" :class="{ active: tab === 'config' }" @click="tab = 'config'">
        <span class="tab-num mono">01</span> 运行时配置
      </button>
      <button class="tab" :class="{ active: tab === 'sessions' }" @click="tab = 'sessions'">
        <span class="tab-num mono">02</span> 会话与进程
      </button>
      <button class="tab" :class="{ active: tab === 'audit' }" @click="tab = 'audit'">
        <span class="tab-num mono">03</span> 审计日志
      </button>
    </nav>

    <!-- CONFIG -->
    <section v-if="tab === 'config'" class="panel fade-up" style="animation-delay:80ms">
      <div class="panel-head">
        <h2 class="serif" style="font-size:28px">运行时配置</h2>
        <span class="serial dim">改动仅对新进程生效</span>
      </div>

      <div class="config-grid">
        <article v-for="(v, k) in config" :key="k" class="cfg-card surface" style="overflow:hidden">
          <div class="cfg-head">
            <code class="mono cfg-key">{{ k }}</code>
            <div style="display:flex;gap:6px">
              <button v-if="editingKey !== k" class="btn-ghost" style="padding:3px 8px;font-size:10px" @click="startEdit(k as string)">编辑</button>
              <button class="btn-mini-danger" @click="deleteConfig(k as string)">删除</button>
            </div>
          </div>
          <div v-if="editingKey === k">
            <textarea v-model="editingValue" class="cfg-edit mono"></textarea>
            <div style="display:flex;gap:8px;padding:12px 18px;border-top:1px solid var(--border)">
              <button class="btn-primary" style="padding:6px 12px;font-size:11px" @click="saveEdit">保存</button>
              <button class="btn-ghost" style="padding:6px 12px;font-size:11px" @click="cancelEdit">取消</button>
            </div>
          </div>
          <pre v-else class="cfg-body mono">{{ pretty(v) }}</pre>
        </article>
      </div>

      <div class="surface" style="padding:24px;margin-top:16px">
        <h3 class="serif" style="font-size:20px;margin-bottom:14px"><em style="color:var(--accent)">+</em> 新增配置</h3>
        <div class="add-fields">
          <input v-model="newKey" class="field-raw" placeholder="配置键 (如 omp.flags.tools)" />
          <input v-model="newValue" class="field-raw" placeholder="值 (JSON 或字符串)" />
          <button class="btn-primary" style="white-space:nowrap" :disabled="!newKey" @click="saveConfig">写入</button>
        </div>
      </div>
    </section>

    <!-- SESSIONS -->
    <section v-if="tab === 'sessions'" class="panel fade-up" style="animation-delay:80ms">
      <div class="panel-head">
        <h2 class="serif" style="font-size:28px">会话与进程</h2>
        <span class="serial">池占用 <span class="accent">{{ pool?.usedSlots ?? 0 }}</span> / 10</span>
      </div>

      <div class="surface" style="overflow:hidden">
        <div class="table-head">
          <span>#</span><span>会话 ID</span><span>用户</span><span>仓库</span><span>状态</span><span>更新时间</span><span></span>
        </div>
        <div v-for="(s, idx) in sessions" :key="s.sessionId" class="table-row">
          <span class="dim">{{ String(idx + 1).padStart(2, '0') }}</span>
          <code class="mono accent">{{ s.sessionId?.slice(0, 8) }}</code>
          <span class="mono dim">{{ s.userId }}</span>
          <span class="mono dim">{{ s.repoId }}</span>
          <span class="mono" :class="{ active: s.status === 'active' }">{{ s.status === 'active' ? '活跃' : '归档' }}</span>
          <span class="mono dim">{{ fmtDate(s.lastActiveAt) }}</span>
          <button class="btn-mini-danger" @click="killSession(s.sessionId)">终止</button>
        </div>
      </div>
    </section>

    <!-- AUDIT -->
    <section v-if="tab === 'audit'" class="panel fade-up" style="animation-delay:80ms">
      <div class="panel-head">
        <h2 class="serif" style="font-size:28px">审计日志</h2>
        <span class="serial dim">最近 50 条</span>
      </div>

      <h3 class="serif" style="font-size:20px;margin-bottom:12px">指令记录</h3>
      <div class="surface" style="overflow:hidden">
        <div v-for="p in prompts" :key="'p' + p.id" class="audit-row">
          <span class="mono dim">{{ fmtDate(p.sentAt) }}</span>
          <code class="mono accent">{{ p.sessionId?.slice(0, 8) }}</code>
          <span class="audit-text">{{ (p.promptText || '').slice(0, 200) }}</span>
        </div>
      </div>

      <h3 class="serif" style="font-size:20px;margin:28px 0 12px">工具调用</h3>
      <div class="surface" style="overflow:hidden">
        <div v-for="t in tools" :key="'t' + t.id" class="audit-row">
          <span class="mono dim">{{ fmtDate(t.startedAt) }}</span>
          <code class="mono accent">{{ t.sessionId?.slice(0, 8) }}</code>
          <span class="mono" :class="{ active: !t.isError, err: t.isError }">{{ t.toolName }}{{ t.isError ? ' · 失败' : '' }}</span>
          <span class="audit-text mono dim">{{ (t.arguments || '').slice(0, 160) }}</span>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.admin {
  max-width: 1040px;
  margin: 0 auto;
  padding: 40px 40px 80px;
}

.masthead { margin-bottom: 24px; }
.masthead-row { display: flex; align-items: center; gap: 14px; margin-bottom: 16px; }
.title {
  font-size: 68px;
  font-weight: 300;
  line-height: 0.94;
  letter-spacing: -0.03em;
  font-variation-settings: "opsz" 144, "SOFT" 30;
}
.title em { color: var(--accent); font-style: italic; }

/* Tabs */
.tabs { display: flex; gap: 4px; margin-bottom: 28px; border-bottom: 1px solid var(--border); }
.tab {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  background: transparent;
  border: 0;
  padding: 10px 18px;
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--text-muted);
  cursor: pointer;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  transition: all 140ms ease;
}
.tab:hover { color: var(--text); }
.tab.active { color: var(--accent); border-bottom-color: var(--accent); }
.tab-num {
  font-size: 9px;
  background: var(--bg-sunken);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 1px 6px;
}
.tab.active .tab-num {
  background: var(--accent-soft);
  border-color: var(--accent);
  color: var(--accent);
  font-weight: 600;
}

.panel { margin-top: 8px; }
.panel-head { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 16px; }
.accent { color: var(--accent); font-weight: 600; }
.dim { color: var(--text-faint); }

/* Config grid */
.config-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(360px, 1fr)); gap: 12px; }
.cfg-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 18px;
  border-bottom: 1px solid var(--border);
}
.cfg-key { font-size: 11px; color: var(--accent); font-weight: 600; }
.cfg-body {
  margin: 0;
  padding: 14px 18px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-secondary);
  max-height: 200px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
.cfg-edit {
  width: 100%;
  min-height: 150px;
  padding: 14px 18px;
  border: 0;
  background: var(--bg-sunken);
  color: var(--text);
  font-size: 12px;
  line-height: 1.5;
  resize: vertical;
  outline: none;
}

.add-fields { display: grid; grid-template-columns: 1fr 2fr auto; gap: 10px; align-items: center; }

/* Table */
.table-head, .table-row {
  display: grid;
  grid-template-columns: 40px 100px 60px 1fr 80px 110px 70px;
  gap: 14px;
  align-items: center;
  padding: 12px 18px;
  font-family: var(--font-mono);
  font-size: 11px;
}
.table-head {
  border-bottom: 1px solid var(--border);
  background: var(--bg-sunken);
  font-size: 9px;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--text-muted);
}
.table-row { border-bottom: 1px solid var(--border); color: var(--text-secondary); }
.table-row:last-child { border-bottom: 0; }
.table-row:hover { background: var(--bg-base); }
.active { color: var(--good); font-weight: 600; }
.err { color: var(--danger); }

/* Audit */
.audit-row {
  display: grid;
  grid-template-columns: 90px 70px 140px 1fr;
  gap: 14px;
  padding: 10px 18px;
  border-bottom: 1px solid var(--border);
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--text-secondary);
  align-items: baseline;
}
.audit-row:last-child { border-bottom: 0; }
.audit-text { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

@media (max-width: 800px) {
  .admin { padding: 24px 20px; }
  .config-grid { grid-template-columns: 1fr; }
  .add-fields { grid-template-columns: 1fr; }
}
</style>
