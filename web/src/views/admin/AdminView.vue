<script setup lang="ts">
import { onMounted, ref, computed, watch, defineProps, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useMessage, useDialog, NPagination } from 'naive-ui'
import { api } from '@/api/http'
import { unarchive as unarchiveSession } from '@/api/session'
import { listSessionsPaged, listAuditPaged } from '@/api/admin'
import { OMP_CONFIG_DEFINITIONS, getConfigDef, getConfigValueOptions, CONFIG_KEY_GROUPS, type ConfigItemDef } from '@/api/omp-config'

const props = defineProps<{ initialTab?: 'config' | 'sessions' | 'audit' }>()
const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const msg = useMessage()
const dialog = useDialog()

// 优先级：prop（被 SystemView 嵌入）> 路由 query（独立 /admin 路由）> 默认 config
function resolveTab(): 'config' | 'sessions' | 'audit' {
  if (props.initialTab) return props.initialTab
  const q = route.query.tab
  if (q === 'config' || q === 'sessions' || q === 'audit') return q
  return 'config'
}
const tab = ref<'config' | 'sessions' | 'audit'>(resolveTab())
// 被 SystemView 以 initialTab 嵌入时为 true：此时由外层导航控制 Tab，
// 内部导航需隐藏，且 Tab 切换通过 watch(initialTab) 同步。
const embedded = computed(() => props.initialTab != null)
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
const newKeySearch = ref('')
const editingKey = ref<string | null>(null)
const editingValue = ref('')
const editingCustomValue = ref(false) // 是否使用自定义值而非下拉
const keySearchRef = ref<HTMLElement | null>(null)

// 下拉框定位样式（使用 Teleport 到 body，需要动态计算位置）
const dropdownStyle = computed(() => {
  if (!keySearchRef.value) return {}
  const rect = keySearchRef.value.getBoundingClientRect()
  return {
    position: 'fixed' as const,
    top: `${rect.bottom + 4}px`,
    left: `${rect.left}px`,
    width: `${rect.width}px`,
  }
})

// 过滤后的分组（仅包含有匹配键的组）
const filteredConfigGroups = computed(() => {
  const q = newKeySearch.value.trim().toLowerCase()
  if (!q) return CONFIG_KEY_GROUPS
  return CONFIG_KEY_GROUPS
    .map(g => ({ label: g.label, keys: g.keys.filter(k => {
      const def = getConfigDef(k)
      if (!def) return k.toLowerCase().includes(q)
      return k.toLowerCase().includes(q) || def.label.toLowerCase().includes(q)
    }) }))
    .filter(g => g.keys.length > 0)
})

// 从下拉选项中选择一个配置键
function selectKey(key: string) {
  newKey.value = key
  const def = getConfigDef(key)
  newKeySearch.value = def ? `${def.label} (${key})` : key
  showKeyDropdown.value = false
}

/**
 * 格式化值下拉的显示文本。
 *  - 单个简短值（无换行、不是 JSON 对象）：显示「中文 (英文 value)」
 *  - 多行 / JSON 对象 / 长内容：只显示 label（描述）避免下拉框被拉宽
 */
function formatValueLabel(opt: { value: string; label: string; description?: string }): string {
  const v = opt.value
  const isMultiLine = v.includes('\n')
  const isJsonObject = v.trimStart().startsWith('{') || v.trimStart().startsWith('[')
  if (isMultiLine || isJsonObject) {
    return opt.description ? `${opt.label} (${opt.description})` : opt.label
  }
  return opt.description
    ? `${opt.label} (${v}) — ${opt.description}`
    : `${opt.label} (${v})`
}

// 关闭搜索下拉（点击外部时）
const showKeyDropdown = ref(false)
function onKeySearchFocus() {
  showKeyDropdown.value = true
  // 聚焦时清空搜索，显示完整列表方便重新选择
  if (newKey.value) {
    newKeySearch.value = ''
  }
}
function onClickOutside(e: MouseEvent) {
  const t = e.target as HTMLElement
  // 点击不在输入框或下拉框内时关闭
  if (!t.closest('.cfg-key-search-input') && !t.closest('.cfg-key-dropdown')) {
    showKeyDropdown.value = false
    // 恢复输入框显示文本
    if (newKey.value) {
      const def = getConfigDef(newKey.value)
      newKeySearch.value = def ? `${def.label} (${newKey.value})` : newKey.value
    }
  }
}
onMounted(() => document.addEventListener('click', onClickOutside))
onUnmounted(() => document.removeEventListener('click', onClickOutside))

// 计算当前选中配置键的值选项
const currentValueOptions = computed(() => {
  if (!newKey.value) return []
  return getConfigValueOptions(newKey.value)
})

// 计算当前选中配置键的定义
const currentConfigDef = computed(() => {
  if (!newKey.value) return null
  return getConfigDef(newKey.value)
})

// 计算是否需要自定义值输入（string 类型或无选项的类型）
const needsCustomValue = computed(() => {
  const def = currentConfigDef.value
  if (!def) return true
  if (def.type === 'string') return true
  if (def.type === 'boolean' || def.type === 'enum') return false
  return true
})

// 监听配置键变化，自动选择第一个选项或清空值
watch(newKey, (key) => {
  const options = getConfigValueOptions(key)
  if (options.length > 0 && !needsCustomValue.value) {
    // 有选项时，如果有默认值则设为默认值
    const def = getConfigDef(key)
    if (def?.defaultValue !== undefined && def?.defaultValue !== null) {
      newValue.value = String(def.defaultValue)
    } else {
      newValue.value = options[0].value
    }
  } else {
    newValue.value = ''
  }
})

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

// ---- sessions pagination ----
const sessionPage = ref(0)
const sessionPageSize = ref(20)
const sessionTotal = ref(0)

async function loadSessions() {
  const result = await listSessionsPaged(sessionPage.value, sessionPageSize.value)
  // Apply operator overrides so killed/restored rows stick across reloads.
  const overrides = loadOverrides()
  for (const f of result.items) {
    const ov = overrides[f.sessionId]
    if (ov) f.effectiveStatus = ov
    else {
      const prev = sessions.value.find(s => s.sessionId === f.sessionId)
      if (prev && prev.effectiveStatus) f.effectiveStatus = prev.effectiveStatus
    }
  }
  sessions.value = result.items
  sessionTotal.value = result.total
  pool.value = (await api.get('/admin/pool')).data
}

function onSessionPageChange(page: number) {
  sessionPage.value = page - 1  // naive-ui uses 1-based, backend uses 0-based
  loadSessions()
}

function onSessionPageSizeChange(size: number) {
  sessionPageSize.value = size
  sessionPage.value = 0
  loadSessions()
}

// ---- audit pagination ----
const auditPage = ref(0)
const auditPageSize = ref(50)
const auditTotal = ref(0)
const auditItems = ref<any[]>([])

async function loadAudit() {
  const result = await listAuditPaged(auditPage.value, auditPageSize.value)
  auditItems.value = result.items
  auditTotal.value = result.total
  autoCollapse()
}

function onAuditPageChange(page: number) {
  auditPage.value = page - 1
  loadAudit()
}

function onAuditPageSizeChange(size: number) {
  auditPageSize.value = size
  auditPage.value = 0
  loadAudit()
}

// Persistent optimistic status — survives page reload.
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

async function loadAll() { await Promise.all([loadConfig(), loadSessions(), loadAudit()]) }

/** Group audit items by sessionId, each group sorted by time descending. */
const auditGroups = computed(() => {
  const map = new Map<string, { sessionId: string; items: any[]; lastTime: number }>()
  const group = (sid: string) => {
    let g = map.get(sid)
    if (!g) { g = { sessionId: sid, items: [], lastTime: 0 }; map.set(sid, g) }
    return g
  }
  for (const item of auditItems.value) {
    const g = group(item.sessionId)
    g.items.push(item)
    const t = item._time ? Date.parse(item._time) : 0
    if (t > g.lastTime) g.lastTime = t
  }
  const groups: { sessionId: string; items: any[]; lastTime: number }[] = []
  for (const g of map.values()) {
    g.items.sort((a: any, b: any) => {
      const ta = a._time ? Date.parse(a._time) : 0
      const tb = b._time ? Date.parse(b._time) : 0
      return tb - ta
    })
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

/** 重置所有配置到默认值 */
async function resetAllConfig() {
  try {
    // 删除所有运行时配置
    const keys = Object.keys(config.value)
    for (const key of keys) {
      await api.delete('/admin/config/' + encodeURIComponent(key))
    }
    msg.success('已重置所有配置到默认值')
    await loadConfig()
  } catch (e: any) {
    msg.error(e?.response?.data?.error || '重置失败')
  }
}

/** 确认重置所有配置 */
function confirmResetAllConfig() {
  const count = Object.keys(config.value).length
  if (count === 0) {
    msg.info('当前没有运行时配置')
    return
  }
  dialog.warning({
    title: '重置配置',
    content: `确认重置全部 ${count} 项运行时配置到默认值？此操作不可撤销，仅对新进程生效。`,
    positiveText: '全部重置',
    negativeText: '取消',
    onPositiveClick: async () => {
      await resetAllConfig()
    },
  })
}

/** Confirm before deleting a runtime config entry. */
function confirmDeleteConfig(key: string) {
  dialog.warning({
    title: '删除配置',
    content: `确认删除运行时配置「${key}」？此操作不可撤销，仅对新进程生效。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteConfig(key)
      } catch (e: any) {
        msg.error(e?.response?.data?.error || '删除失败')
      }
    },
  })
}

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

function fmtDate(s?: string | number) {
  if (!s) return '—'
  const d = new Date(s)
  return `${String(d.getMonth() + 1).padStart(2, '0')}.${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
function pretty(v: any): string { if (v == null) return '—'; if (typeof v === 'string') return v; return JSON.stringify(v, null, 2) }

function logout() { auth.logout(); router.replace('/login') }

/** 选中第一个有权限的 Tab，避免停留在无权限的空白页。 */
function pickInitialTab() {
  const order: { key: typeof tab.value; perm: string }[] = [
    { key: 'config', perm: 'omp:config:view' },
    { key: 'sessions', perm: 'omp:sessions:view' },
    { key: 'audit', perm: 'omp:audit:view' },
  ]
  const first = order.find(o => auth.hasPerm(o.perm))
  if (first) tab.value = first.key
}

// 嵌入模式下由外层（SystemView）导航驱动：initialTab 变化时同步内部 Tab。
watch(() => props.initialTab, (v) => {
  if (v != null) tab.value = v
})

// 独立路由模式下，侧边栏切换子菜单会改变 query.tab，同步到内部 Tab。
watch(() => route.query.tab, (v) => {
  if (embedded.value) return
  if (v === 'config' || v === 'sessions' || v === 'audit') tab.value = v
})

// 切换到某个 Tab 时重新拉取对应数据，保证看到最新状态
// （如在「模型配置」激活模型后回到「运行时配置」能看到 model.active 更新）
watch(tab, (v) => {
  if (v === 'config') loadConfig()
  else if (v === 'sessions') loadSessions()
  else if (v === 'audit') loadAudit()
})

onMounted(() => {
  // 嵌入模式：保留外层 prop 传入的 Tab。
  // 独立路由：若 query 未显式指定 tab，则按权限选第一个可见 Tab 兜底。
  if (!embedded.value && !route.query.tab) pickInitialTab()
  loadAll()
})
</script>

<template>
  <div class="page admin-embed">
    <!-- Tabs：嵌入 SystemView 时由外层导航控制，隐藏内部重复导航 -->
    <nav v-if="!embedded" class="tabs fade-up" style="animation-delay:0ms">
      <button v-if="auth.hasPerm('omp:config:view')" class="tab" :class="{ active: tab === 'config' }" @click="tab = 'config'">
        <span class="tab-num mono">01</span>
        <span>运行时配置</span>
      </button>
      <button v-if="auth.hasPerm('omp:sessions:view')" class="tab" :class="{ active: tab === 'sessions' }" @click="tab = 'sessions'">
        <span class="tab-num mono">02</span>
        <span>会话与进程</span>
      </button>
      <button v-if="auth.hasPerm('omp:audit:view')" class="tab" :class="{ active: tab === 'audit' }" @click="tab = 'audit'">
        <span class="tab-num mono">03</span>
        <span>审计日志</span>
      </button>
    </nav>

    <!-- ============================ CONFIG ============================ -->
    <section v-if="tab === 'config'" class="panel fade-up" style="animation-delay:340ms">
      <div class="config-grid">
        <article v-for="(v, k) in config" :key="k" class="cfg-card card">
          <code class="mono cfg-key">{{ k }}</code>
          <div class="cfg-value">
            <div v-if="editingKey === k" class="cfg-edit-wrap">
              <textarea v-model="editingValue" class="cfg-edit mono field-raw" />
              <div class="cfg-edit-foot">
                <button class="btn-primary" @click="saveEdit">保存</button>
                <button class="btn-ghost" @click="cancelEdit">取消</button>
              </div>
            </div>
            <pre v-else class="cfg-body mono">{{ pretty(v) }}</pre>
          </div>
          <div class="cfg-actions">
            <button v-if="editingKey !== k" v-permission="'omp:config:edit'" class="btn-ghost btn-xs" @click="startEdit(k as string)">编辑</button>
            <button v-permission="'omp:config:delete'" class="btn-mini-danger" @click="confirmDeleteConfig(k as string)">删除</button>
          </div>
        </article>
      </div>

      <div v-permission="'omp:config:edit'" class="card cfg-add">
        <div class="cfg-add-head">
          <span class="tag">新增配置</span>
          <button class="btn-mini-danger" @click="confirmResetAllConfig">重置所有配置</button>
        </div>
        <h3 class="cfg-add-title">写入一条<strong>运行时配置</strong></h3>
        <p class="cfg-add-desc">选择 omp 支持的配置项，值将自动提供可选选项</p>
        <div class="add-fields">
          <div class="cfg-key-search" ref="keySearchRef">
            <input
              v-model="newKeySearch"
              @focus="onKeySearchFocus"
              class="field-raw cfg-key-search-input"
              :placeholder="newKey ? undefined : '搜索配置键 (中文或英文，如「主题」「theme」)'"
            />
            <Teleport to="body">
              <div
                v-if="showKeyDropdown"
                class="cfg-key-dropdown"
                :style="dropdownStyle"
                @click.stop
              >
                <div v-if="filteredConfigGroups.length === 0" class="cfg-key-empty">无匹配结果</div>
                <div
                  v-for="group in filteredConfigGroups"
                  :key="group.label"
                  class="cfg-key-group"
                >
                  <div class="cfg-key-group-label">{{ group.label }}</div>
                  <div
                    v-for="k in group.keys"
                    :key="k"
                    class="cfg-key-option"
                    :class="{ active: newKey === k }"
                    @click="selectKey(k)"
                  >
                    <span class="cfg-key-option-label">{{ getConfigDef(k)?.label || k }}<span class="cfg-key-option-key"> ({{ k }})</span></span>
                  </div>
                </div>
              </div>
            </Teleport>
          </div>
          <template v-if="needsCustomValue">
            <input v-model="newValue" class="field-raw" :placeholder="currentConfigDef?.type === 'string' ? '输入字符串值' : '输入值 (JSON 或字符串)'"/>
          </template>
          <template v-else>
            <select v-model="newValue" class="field-raw cfg-value-select">
              <option value="" disabled>-- 选择值 --</option>
              <option v-for="opt in currentValueOptions" :key="opt.value" :value="opt.value">
                {{ formatValueLabel(opt) }}
              </option>
            </select>
          </template>
          <button class="btn-primary" :disabled="!newKey || !newValue" @click="saveConfig">写入</button>
        </div>
        <div v-if="currentConfigDef" class="cfg-add-info">
          <p class="cfg-info-desc">{{ currentConfigDef.description }}</p>
          <p v-if="currentConfigDef.defaultValue !== undefined && currentConfigDef.defaultValue !== null" class="cfg-info-default">
            默认值: <code>{{ currentConfigDef.defaultValue }}</code>
          </p>
        </div>
      </div>
    </section>

    <!-- ============================ SESSIONS ============================ -->
    <section v-if="tab === 'sessions'" class="panel fade-up" style="animation-delay:340ms">
      <header class="panel-head">
        <div>
          <p class="panel-sub">所有沙箱会话。可强制终止异常进程。</p>
        </div>
        <div class="panel-head-actions">
          <button
            v-permission="'omp:sessions:kill'"
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
          <span class="dim">{{ String(sessionPage * sessionPageSize + idx + 1).padStart(2, '0') }}</span>
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
            v-permission="'omp:sessions:kill'"
            class="btn-mini-danger"
            @click="confirmKillSession(s.sessionId)"
          >终止</button>
          <button
            v-else
            v-permission="'omp:sessions:restore'"
            class="btn-mini-primary"
            @click="confirmRestoreSession(s.sessionId)"
          >恢复</button>
        </div>
        <div class="pagination-wrap">
          <n-pagination
            :page="sessionPage + 1"
            :page-size="sessionPageSize"
            :item-count="sessionTotal"
            :page-sizes="[10, 20, 50]"
            show-size-picker
            @update:page="onSessionPageChange"
            @update:page-size="onSessionPageSizeChange"
          />
        </div>
      </div>
    </section>

    <!-- ============================ AUDIT ============================ -->
    <section v-if="tab === 'audit'" class="panel fade-up" style="animation-delay:340ms">
      <header class="panel-head">
        <div>
          <p class="panel-sub">指令、工具调用与 OMP 回复，按会话分组展示。</p>
        </div>
        <span class="serial dim">{{ auditTotal }} 条</span>
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
      <div v-if="auditTotal > 0" class="pagination-wrap">
        <n-pagination
          :page="auditPage + 1"
          :page-size="auditPageSize"
          :item-count="auditTotal"
          :page-sizes="[20, 50, 100]"
          show-size-picker
          @update:page="onAuditPageChange"
          @update:page-size="onAuditPageSizeChange"
        />
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

/* When embedded in SystemView, no outer padding or max-width */
.admin-embed {
  padding: 0;
  width: 100%;
  max-width: none;
  gap: 16px;
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
  grid-template-columns: 1fr;
  gap: 12px;
}
.cfg-card {
  overflow: hidden;
  display: grid;
  grid-template-columns: minmax(140px, max-content) 1fr auto;
  align-items: stretch;
}
.cfg-key {
  display: flex;
  align-items: center;
  padding: 12px 18px;
  font-size: 12px;
  color: var(--brand);
  font-weight: 600;
  background: var(--surface-soft);
  border-right: 1px solid var(--border);
  word-break: break-all;
}
.cfg-value { min-width: 0; }
.cfg-actions {
  display: flex;
  gap: 6px;
  align-items: center;
  padding: 12px 18px;
  border-left: 1px solid var(--border);
  background: var(--surface-soft);
}
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
.cfg-edit-wrap { display: flex; flex-direction: column; height: 100%; }
.cfg-edit {
  border-radius: 0 !important;
  border: 0 !important;
  width: 100%;
  min-height: 120px;
  flex: 1;
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

.cfg-add { padding: 24px; display: flex; flex-direction: column; gap: 12px; overflow: visible; }
.cfg-add-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.add-fields { display: grid; grid-template-columns: 1fr 2fr auto; gap: 10px; align-items: center; }
.cfg-add-title {
  font-family: var(--font-display);
  font-size: 18px;
  font-weight: 600;
  margin: 0;
}
.cfg-add-title strong { color: var(--brand); font-weight: 700; }
.cfg-add-desc {
  margin: 0;
  font-size: 12px;
  color: var(--ink-mute);
}
.cfg-add-info {
  margin-top: 4px;
  padding: 12px 16px;
  background: var(--surface-soft);
  border: 1px solid var(--border);
  border-radius: 6px;
  font-size: 12px;
}
.cfg-info-desc {
  margin: 0 0 6px;
  color: var(--ink-2);
}
.cfg-info-default {
  margin: 0;
  color: var(--ink-mute);
}
.cfg-info-default code {
  padding: 1px 6px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 3px;
  color: var(--brand);
  font-size: 11px;
}
.cfg-key-select,
.cfg-value-select {
  font-family: var(--font-mono);
  font-size: 12px;
  appearance: none;
  -webkit-appearance: none;
  background-image: url("data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 24 24' fill='none' stroke='%236b7280' stroke-width='2'%3E%3Cpolyline points='6 9 12 15 18 9'%3E%3C/polyline%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: right 10px center;
  background-size: 12px;
  padding-right: 32px;
  cursor: pointer;
}
.cfg-value-select { font-family: var(--font-sans, sans-serif); }

/* Searchable config key picker */
.cfg-key-search { position: relative; width: 100%; }
.cfg-key-search-input { width: 100%; cursor: text; }
.cfg-key-dropdown {
  max-height: 400px;
  overflow-y: auto;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 6px;
  box-shadow: 0 6px 20px rgba(0,0,0,0.12);
  z-index: 1000;
  padding: 4px 0;
}
.cfg-key-empty {
  padding: 16px;
  text-align: center;
  color: var(--ink-mute);
  font-size: 12px;
}
.cfg-key-group { padding: 4px 0; }
.cfg-key-group-label {
  padding: 6px 12px 4px;
  font-size: 10px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--ink-mute);
  font-weight: 600;
}
.cfg-key-option {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 6px 12px;
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease-out);
}
.cfg-key-option:hover { background: var(--surface-soft); }
.cfg-key-option.active { background: var(--brand-soft); }
.cfg-key-option-label {
  font-size: 13px;
  color: var(--ink);
  font-weight: 500;
}
.cfg-key-option-key {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--ink-mute);
}

/* ====================================================================
   Table (sessions)
   ==================================================================== */
.table-card { overflow: hidden; padding: 0; }
.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  padding: 10px 16px;
  border-top: 1px solid var(--border);
}
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
  .cfg-card { grid-template-columns: 1fr; }
  .cfg-key { border-right: 0; border-bottom: 1px solid var(--border); }
  .cfg-actions { border-left: 0; border-top: 1px solid var(--border); justify-content: flex-end; }
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