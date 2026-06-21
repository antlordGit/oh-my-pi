<script setup lang="ts">
import { ref, onMounted, provide, computed, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { listUsers, listTenants, listRoles, listMenus, listModelConfigs } from '@/api/system'
import UserList from './user/UserList.vue'
import TenantList from './tenant/TenantList.vue'
import RoleList from './role/RoleList.vue'
import MenuTree from './menu/MenuTree.vue'
import ModelConfigList from './model/ModelConfigList.vue'
import AdminView from '@/views/admin/AdminView.vue'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

type TabKey = 'user' | 'tenant' | 'role' | 'menu' | 'model' | 'admin-config' | 'admin-sessions' | 'admin-audit'

// 每个 Tab 绑定其菜单级权限码；用户无该权限则 Tab 不显示。
const ALL_TABS: { key: TabKey; label: string; num: string; perm: string; group: 'admin' | 'system' }[] = [
  { key: 'admin-config', label: '运行时配置', num: '01', perm: 'omp:config:view', group: 'admin' },
  { key: 'admin-sessions', label: '会话与进程', num: '02', perm: 'omp:sessions:view', group: 'admin' },
  { key: 'admin-audit', label: '审计日志', num: '03', perm: 'omp:audit:view', group: 'admin' },
  { key: 'user', label: '用户管理', num: '04', perm: 'omp:system:user:list', group: 'system' },
  { key: 'tenant', label: '租户管理', num: '05', perm: 'omp:system:tenant:list', group: 'system' },
  { key: 'role', label: '角色管理', num: '06', perm: 'omp:system:role:list', group: 'system' },
  { key: 'menu', label: '菜单管理', num: '07', perm: 'omp:system:menu:list', group: 'system' },
  { key: 'model', label: '模型配置', num: '08', perm: 'omp:system:model:list', group: 'system' },
]

const visibleTabs = computed(() => ALL_TABS.filter(t => auth.hasPerm(t.perm)))
const activeTab = ref<TabKey>('admin-config')

// 根据路由 query.tab 同步激活的 Tab（顶部菜单导航传入）
function syncTabFromRoute() {
  const q = route.query.tab as string | undefined
  if (!q) return
  const matched = visibleTabs.value.find(t => t.key === q)
  if (matched) activeTab.value = matched.key
}

watch(() => route.query.tab, syncTabFromRoute)

// Map admin sub-tab keys to the initial tab passed to AdminView
const adminInitialTab = computed<'config' | 'sessions' | 'audit' | undefined>(() => {
  if (activeTab.value === 'admin-config') return 'config'
  if (activeTab.value === 'admin-sessions') return 'sessions'
  if (activeTab.value === 'admin-audit') return 'audit'
  return undefined
})

// --- stats ---
const userCount = ref(0)
const tenantCount = ref(0)
const roleCount = ref(0)
const menuCount = ref(0)
const modelCount = ref(0)

async function refreshStats() {
  try {
    const [users, tenants, roles, menus, models] = await Promise.all([
      listUsers(),
      listTenants(),
      listRoles(),
      listMenus(true),
      listModelConfigs(),
    ])
    userCount.value = users.length
    tenantCount.value = tenants.length
    roleCount.value = roles.length
    function countMenu(items: typeof menus): number {
      let n = 0
      for (const m of items) { n += 1 + countMenu(m.children || []) }
      return n
    }
    menuCount.value = countMenu(menus)
    modelCount.value = models.length
  } catch { /* graceful */ }
}

provide('refreshStats', refreshStats)

onMounted(() => {
  // 无任何可见 Tab（既非管理员、也未授权任何系统管理子菜单）时退回工作台
  if (!visibleTabs.value.length) {
    router.replace('/sessions')
    return
  }
  // 默认选中第一个有权限的 Tab
  if (!visibleTabs.value.some(t => t.key === activeTab.value)) {
    activeTab.value = visibleTabs.value[0].key
  }
  // 路由带 tab 参数时优先同步
  syncTabFromRoute()
  refreshStats()
})
</script>

<template>
  <div class="system-root">
    <div class="page">

      <!-- Hero stats -->
      <section class="hero-stats-section fade-up" style="animation-delay:160ms">
        <div class="hero-stats">
          <div class="stat-cell stat-users">
            <div class="stat-ring">
              <span class="big-num">{{ String(userCount).padStart(2, '0') }}</span>
            </div>
            <div class="stat-foot">
              <span class="serial">用户</span>
            </div>
          </div>
          <div class="stat-cell stat-tenants">
            <div class="stat-ring">
              <span class="big-num">{{ String(tenantCount).padStart(2, '0') }}</span>
            </div>
            <div class="stat-foot">
              <span class="serial">租户</span>
            </div>
          </div>
          <div class="stat-cell stat-roles">
            <div class="stat-ring">
              <span class="big-num">{{ String(roleCount).padStart(2, '0') }}</span>
            </div>
            <div class="stat-foot">
              <span class="serial">角色</span>
            </div>
          </div>
          <div class="stat-cell stat-menus">
            <div class="stat-ring">
              <span class="big-num">{{ String(menuCount).padStart(2, '0') }}</span>
            </div>
            <div class="stat-foot">
              <span class="serial">菜单</span>
            </div>
          </div>
          <div class="stat-cell stat-models">
            <div class="stat-ring">
              <span class="big-num">{{ String(modelCount).padStart(2, '0') }}</span>
            </div>
            <div class="stat-foot">
              <span class="serial">模型</span>
            </div>
          </div>
        </div>
      </section>

      <!-- Tabs -->
      <nav class="tabs fade-up" style="animation-delay:240ms">
        <button
          v-for="t in visibleTabs"
          :key="t.key"
          class="tab"
          :class="{ active: activeTab === t.key, 'tab-group-sep': t.key === 'user' }"
          @click="activeTab = t.key as any"
        >
          <span class="tab-num mono">{{ t.num }}</span>
          <span>{{ t.label }}</span>
        </button>
      </nav>

      <!-- Content -->
      <section class="section child-area fade-up" style="animation-delay:300ms">
        <AdminView v-if="activeTab.startsWith('admin-')" :initial-tab="adminInitialTab" />
        <UserList v-else-if="activeTab === 'user'" />
        <TenantList v-else-if="activeTab === 'tenant'" />
        <RoleList v-else-if="activeTab === 'role'" />
        <MenuTree v-else-if="activeTab === 'menu'" />
        <ModelConfigList v-else-if="activeTab === 'model'" />
      </section>
    </div>
  </div>
</template>

<style scoped>
.system-root { position: relative; z-index: 1; }
.page {
  max-width: min(1600px, 95vw);
  margin: 0 auto;
  padding: 24px 32px 80px;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

/* Topbar */
.topbar {
  display: flex; align-items: center; gap: 24px;
  padding: 16px 0; position: sticky; top: 0;
  background: rgba(255,255,255,0.86);
  backdrop-filter: saturate(180%) blur(16px);
  -webkit-backdrop-filter: saturate(180%) blur(16px);
  z-index: 10;
}
.brand { display: inline-flex; align-items: center; gap: 8px; }
.brand-mark {
  display: inline-flex; align-items: center; justify-content: center;
  width: 32px; height: 32px;
  background: rgba(22,93,255,0.10); border-radius: 8px;
}
.brand-name { font-size: 18px; font-weight: 700; letter-spacing: -0.01em; }
.nav-search {
  display: inline-flex; align-items: center; gap: 8px;
  padding: 6px 14px; border: 1px solid var(--border);
  border-radius: var(--radius-pill); background: var(--surface-soft);
  margin-left: 24px;
  transition: border-color var(--dur-fast) var(--ease-out);
}
.nav-search:focus-within { border-color: var(--brand); background: var(--surface); }
.search-icon { color: var(--ink-mute); font-size: 14px; }
.search-input {
  border: 0; background: transparent; outline: 0;
  font-size: 13px; width: 200px; color: var(--ink);
}
.search-input::placeholder { color: var(--ink-mute); }
.nav-actions { margin-left: auto; display: inline-flex; align-items: center; gap: 12px; }
.nav-user { display: inline-flex; align-items: center; }
.nav-user .serial { color: var(--ink-2); }

/* Hero stats */
.hero-stats-section { padding: 12px 0 8px; position: relative; z-index: 1; }
.hero-stats {
  display: grid; grid-template-columns: repeat(5, 1fr);
  gap: 1px; background: var(--border);
  border: 1px solid var(--border);
  border-radius: var(--radius); overflow: hidden;
}
.stat-cell {
  display: flex; flex-direction: column; align-items: center;
  gap: 14px; padding: 28px 16px 22px;
  background: var(--surface); position: relative;
}
.stat-ring {
  position: relative; display: inline-flex;
  align-items: center; justify-content: center;
  width: 88px; height: 88px; border-radius: 50%;
}
.stat-ring::before {
  content: ''; position: absolute; inset: 0;
  border-radius: 50%; border: 1.5px solid var(--border);
}
.big-num {
  font-family: var(--font-mono);
  font-size: clamp(36px, 3.6vw, 48px);
  font-weight: 700; letter-spacing: -0.04em;
  color: var(--brand); line-height: 1;
  position: relative; z-index: 1;
  font-variant-numeric: tabular-nums;
}
.stat-users .big-num  { color: var(--brand); }
.stat-tenants .big-num  { color: #7C3AED; }
.stat-roles .big-num    { color: #16A34A; }
.stat-menus .big-num    { color: #EA580C; }
.stat-models .big-num   { color: #0891B2; }
.stat-foot {
  display: flex; align-items: center; gap: 8px;
  font-size: 11px; font-weight: 500; letter-spacing: 0.08em;
  text-transform: uppercase; color: var(--ink-mute);
}

/* Tabs */
.tabs {
  display: flex; gap: 0;
  border-bottom: 1px solid var(--border);
}
.tab {
  display: inline-flex; align-items: center; gap: 8px;
  background: transparent; border: 0;
  padding: 12px 20px; font-size: 14px; font-weight: 500;
  color: var(--ink-mute); cursor: pointer;
  border-bottom: 2px solid transparent; margin-bottom: -1px;
  transition: color var(--dur-fast) var(--ease-out), border-color var(--dur-fast) var(--ease-out);
}
.tab:hover { color: var(--brand); }
.tab.active { color: var(--brand); border-bottom-color: var(--brand); font-weight: 600; }
.tab-group-sep { border-left: 1px solid var(--border); margin-left: 8px; padding-left: 20px; }
.tab-num {
  font-size: 11px; background: var(--surface-soft);
  border: 1px solid var(--border); border-radius: 4px; padding: 1px 6px;
}
.tab.active .tab-num {
  background: var(--brand-soft); border-color: var(--brand-soft-2);
  color: var(--brand); font-weight: 600;
}

/* Section */
.section { display: flex; flex-direction: column; gap: 16px; }

/* Mobile */
@media (max-width: 900px) {
  .page { padding: 0 16px 48px; }
  .topbar { flex-wrap: wrap; gap: 12px; }
  .nav-search { display: none; }
  .hero-stats { grid-template-columns: repeat(3, 1fr); }
  .stat-cell { padding: 20px 14px 18px; gap: 10px; }
  .stat-ring { width: 72px; height: 72px; }
  .big-num { font-size: 32px; }
}
@media (max-width: 600px) {
  .hero-stats { grid-template-columns: repeat(2, 1fr); }
}
</style>

<!-- Non-scoped: shared styles for child components.
     Scoped by .child-area to avoid leaking outside the system page. -->
<style>
.child-area .header {
  display: flex; justify-content: space-between; align-items: center;
  gap: 16px; flex-wrap: wrap;
}
.child-area .title {
  font-size: 18px; font-weight: 700; margin: 0; color: var(--ink);
}
.child-area .card {
  background: var(--surface); border: 1px solid var(--border);
  border-radius: var(--radius); overflow: hidden;
}
.child-area .card:hover { transform: none; }
.child-area .table-head,
.child-area .table-row {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 16px; font-size: 12px;
}
.child-area .table-head {
  border-bottom: 1px solid var(--border); background: var(--surface-soft);
  font-size: 10px; letter-spacing: 0.06em; text-transform: uppercase;
  color: var(--ink-mute);
}
.child-area .table-row {
  border-bottom: 1px solid var(--border); color: var(--ink-2);
  transition: background var(--dur-fast) var(--ease-out);
}
.child-area .table-row:last-child { border-bottom: 0; }
.child-area .table-row:hover { background: var(--surface-hover); }
.child-area .mono { font-family: var(--font-mono); }
.child-area .dim { color: var(--ink-mute); }
.child-area .accent { color: var(--brand); font-weight: 600; }
.child-area .tag {
  display: inline-flex; align-items: center; padding: 2px 10px;
  border-radius: var(--radius-pill); font-size: 11px; font-weight: 500;
  background: var(--brand-soft); color: var(--brand);
  border: 1px solid var(--brand-soft-2);
}
.child-area .tag-success {
  background: var(--good-soft); color: var(--good); border-color: var(--good-soft);
}
.child-area .tag-danger {
  background: var(--danger-soft); color: var(--danger); border-color: var(--danger-soft);
}
.child-area .tag-mute {
  background: var(--surface-soft); color: var(--ink-mute); border: 1px solid var(--border);
}
.child-area .btn-primary {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 8px 18px; border-radius: var(--radius-pill); border: 0;
  background: var(--brand); color: #fff; font-size: 13px; font-weight: 600;
  cursor: pointer; transition: background var(--dur-fast);
}
.child-area .btn-primary:hover { background: var(--brand-hover); }
.child-area .btn-mini {
  padding: 4px 12px; border-radius: var(--radius-pill);
  border: 1px solid var(--border); background: var(--surface);
  font-size: 11px; font-weight: 500; cursor: pointer;
  color: var(--brand); transition: all var(--dur-fast);
}
.child-area .btn-mini:hover { background: var(--brand-soft); border-color: var(--brand-soft-2); }
.child-area .btn-mini-danger {
  padding: 4px 12px; border-radius: var(--radius-pill);
  border: 1px solid var(--danger-soft); background: var(--surface);
  font-size: 11px; font-weight: 500; cursor: pointer;
  color: var(--danger); transition: all var(--dur-fast);
}
.child-area .btn-mini-danger:hover { background: var(--danger-soft); }
.child-area .empty {
  padding: 56px 24px; text-align: center;
  display: flex; flex-direction: column; align-items: center; gap: 6px;
}
.child-area .empty-icon { font-size: 48px; color: var(--ink-faint); }
.child-area .empty-text { font-size: 16px; font-weight: 600; color: var(--ink-2); margin: 4px 0; }
.child-area .expand-btn {
  width: 18px; font-size: 11px; cursor: pointer; color: var(--ink-mute);
  text-align: center; flex-shrink: 0; user-select: none;
  transition: color var(--dur-fast);
}
.child-area .expand-btn:hover { color: var(--brand); }
.child-area .tree-indent { flex-shrink: 0; }
</style>
