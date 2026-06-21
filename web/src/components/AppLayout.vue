<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import type { MenuInfo } from '@/api/system'
import { useAuthStore } from '@/stores/auth'
import { useMenuStore } from '@/stores/menu'
import { isDark, toggleTheme } from '@/stores/theme'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const menuStore = useMenuStore()

const expandedIds = ref<Set<number>>(new Set())
const mobileMenuOpen = ref(false)

function visibleChildren(menus: MenuInfo[] | undefined): MenuInfo[] {
  if (!menus) return []
  return menus
    .filter(m => m.enabled && m.menuType === 'menu')
    .sort((a, b) => a.sortOrder - b.sortOrder)
}

// 顶部导航隐藏的菜单 code（控制室仅作管理入口，不在顶部展示）
const HIDDEN_TOP_CODES = new Set(['omp:control'])

const topMenus = computed<MenuInfo[]>(() => {
  const roots = menuStore.menus
  const list = (roots.length === 1 && roots[0].parentId == null)
    ? visibleChildren(roots[0].children)
    : visibleChildren(roots)
  return list.filter(m => !HIDDEN_TOP_CODES.has(m.menuCode))
})

function isExpanded(id: number): boolean {
  return expandedIds.value.has(id)
}

// 叶子菜单：精确匹配当前路由
function isActive(menu: MenuInfo): boolean {
  if (!menu.path) return false
  return route.fullPath === resolveTarget(menu)
}

// 顶级/父级菜单：路由前缀命中即高亮
function isMenuActive(menu: MenuInfo): boolean {
  return isPathMatch(menu)
}

function toggleExpand(id: number) {
  // 互斥展开：点击时若已展开则收起，否则只展开当前
  if (expandedIds.value.has(id)) {
    expandedIds.value = new Set()
  } else {
    expandedIds.value = new Set([id])
  }
}

function menuChildren(menu: MenuInfo): MenuInfo[] {
  return visibleChildren(menu.children)
}

function navItemClass(menu: MenuInfo): Record<string, boolean> {
  return {
    active: isMenuActive(menu),
    'has-children': menuChildren(menu).length > 0,
    expanded: isExpanded(menu.id),
  }
}

function navigateTo(menu: MenuInfo) {
  const hasChildren = menuChildren(menu).length > 0
  const target = menu.path ? resolveTarget(menu) : null
  // 同路由再次点击父级：仅切换展开状态
  if (target && route.fullPath === target && hasChildren) {
    toggleExpand(menu.id)
    return
  }
  if (target) {
    router.push(target)
  } else if (hasChildren) {
    toggleExpand(menu.id)
  }
}

function navigateToChild(child: MenuInfo) {
  if (child.path) router.push(resolveTarget(child))
  expandedIds.value.clear()
}

/**
 * 解析菜单的实际导航目标。
 * 系统管理子菜单 path 统一为 /system，需根据 menuCode 末段附加 ?tab=xxx 区分。
 */
function resolveTarget(menu: MenuInfo): string {
  if (!menu.path) return '/'
  // 已带 query（如控制室 /admin?tab=config）直接使用
  if (menu.path.includes('?')) return menu.path
  // /system 下的子菜单：用 menuCode 末段作为 tab
  if (menu.path === '/system' && menu.menuCode) {
    const seg = menu.menuCode.split(':').pop()
    if (seg && seg !== 'system') return `/system?tab=${seg}`
  }
  return menu.path
}

function logout() {
  auth.logout()
  router.replace('/login')
}

function initExpanded() {
  const set = new Set<number>()
  function walk(items: MenuInfo[]) {
    for (const m of items) {
      const children = menuChildren(m)
      if (children.length > 0) {
        // 父级自身路由命中，或任一子项命中，则展开
        if (isPathMatch(m) || children.some(c => isActive(c))) set.add(m.id)
        walk(children)
      }
    }
  }
  walk(topMenus.value)
  expandedIds.value = set
}

// 父级菜单的 path（去 query）是否为当前路由的前缀
function isPathMatch(menu: MenuInfo): boolean {
  if (!menu.path) return false
  const base = menu.path.split('?')[0]
  if (base === '/' || base === '/sessions') return route.path === base
  return route.path === base || route.path.startsWith(base + '/')
}

watch(() => menuStore.loaded, (loaded) => { if (loaded) initExpanded() })

// 路由变化时同步展开状态（保证当前页面对应的父级展开，其余收起）
watch(() => route.fullPath, () => initExpanded())

onMounted(() => {
  if (menuStore.loaded) initExpanded()
})
</script>

<template>
  <div class="app-layout">
    <header class="app-header">
      <div class="header-left">
        <div class="brand">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none">
            <path d="M4 20 L12 4 L20 20 L16 20 L12 12 L8 20 Z" fill="var(--brand)"/>
          </svg>
          <span class="brand-name">OMP</span>
          <span class="brand-tag">WORKSTATION</span>
        </div>

        <button class="hamburger hide-desktop" @click.stop="mobileMenuOpen = !mobileMenuOpen" :aria-label="mobileMenuOpen ? '关闭菜单' : '打开菜单'">
          <span class="hamburger-line" :class="{ open: mobileMenuOpen }"></span>
          <span class="hamburger-line" :class="{ open: mobileMenuOpen }"></span>
          <span class="hamburger-line" :class="{ open: mobileMenuOpen }"></span>
        </button>

        <nav class="top-nav">
          <template v-for="menu in topMenus" :key="menu.id">
            <div class="nav-item-wrapper">
              <div
                class="nav-item"
                :class="navItemClass(menu)"
                @click="navigateTo(menu)"
              >
                <span class="nav-name">{{ menu.menuName }}</span>
                <span
                  v-if="menuChildren(menu).length > 0"
                  class="nav-arrow"
                  :class="{ open: isExpanded(menu.id) }"
                >
                  <svg width="10" height="6" viewBox="0 0 10 6" fill="none">
                    <path d="M1 1L5 5L9 1" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                  </svg>
                </span>
              </div>

              <Transition name="panel">
                <div
                  v-if="isExpanded(menu.id) && menuChildren(menu).length > 0"
                  class="nav-panel"
                >
                  <div class="panel-divider"/>
                  <div class="panel-grid">
                    <div
                      v-for="child in menuChildren(menu)"
                      :key="child.id"
                      class="panel-item"
                      :class="{ active: isActive(child) }"
                      @click="navigateToChild(child)"
                    >
                      <span class="panel-label">{{ child.menuName }}</span>
                      <span class="panel-corner"/>
                    </div>
                  </div>
                </div>
              </Transition>
            </div>
          </template>
        </nav>
      </div>

      <div class="header-right">
        <div class="status-dot"/>
        <span class="username">{{ auth.username }}</span>
        <span v-if="auth.isAdmin" class="role-badge">
          {{ auth.isSuperAdmin ? 'SUPERADMIN' : 'ADMIN' }}
        </span>
        <button class="btn-theme" @click="toggleTheme()" :title="isDark ? '切换亮色' : '切换暗色'">
          <svg v-if="isDark" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/>
          </svg>
          <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M21 12.79A9 9 0 1111.21 3 7 7 0 0021 12.79z"/>
          </svg>
        </button>
        <button class="btn-logout" @click="logout">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4"/>
            <polyline points="16 17 21 12 16 7"/>
            <line x1="21" y1="12" x2="9" y2="12"/>
          </svg>
        </button>
      </div>
    </header>

    <!-- Mobile nav drawer -->
    <Transition name="drawer">
      <aside v-if="mobileMenuOpen" class="mobile-nav-drawer">
        <div class="drawer-header">
          <span class="drawer-title">NAVIGATION</span>
          <button class="drawer-close" @click="mobileMenuOpen = false">✕</button>
        </div>
        <nav class="drawer-nav">
          <div v-for="menu in topMenus" :key="menu.id" class="drawer-nav-group">
            <div
              class="drawer-nav-parent"
              :class="{ active: isMenuActive(menu) }"
              @click="navigateTo(menu); mobileMenuOpen = false"
            >
              <span>{{ menu.menuName }}</span>
              <span v-if="menuChildren(menu).length" class="drawer-chevron">›</span>
            </div>
            <div
              v-for="child in menuChildren(menu)"
              :key="child.id"
              class="drawer-nav-child"
              :class="{ active: isActive(child) }"
              @click="navigateToChild(child); mobileMenuOpen = false"
            >
              <span class="drawer-child-dot"></span>
              <span>{{ child.menuName }}</span>
            </div>
          </div>
        </nav>
        <div class="drawer-footer">
          <div class="drawer-user">
            <span class="drawer-status-dot"></span>
            <span class="username">{{ auth.username }}</span>
            <span v-if="auth.isAdmin" class="role-badge">
              {{ auth.isSuperAdmin ? 'SUPERADMIN' : 'ADMIN' }}
            </span>
          </div>
          <div class="drawer-actions">
            <button class="btn-ghost btn-sm" @click="toggleTheme()">
              {{ isDark ? '◑ 亮色' : '◐ 暗色' }}
            </button>
            <button class="btn-mini-danger" @click="logout()">登出</button>
          </div>
        </div>
      </aside>
    </Transition>

    <!-- Drawer backdrop -->
    <Transition name="fade">
      <div v-if="mobileMenuOpen" class="drawer-backdrop" @click="mobileMenuOpen = false"></div>
    </Transition>

    <main class="app-main">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
/* ============================================================
   OMP — Industrial Command Center
   Dark tactical interface for AI coding workstation
   ============================================================ */

.app-layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
  background: var(--canvas);
}

/* ---------- Header ---------- */
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  height: 48px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
  z-index: 100;
  position: relative;
}

.app-header::after {
  content: '';
  position: absolute;
  bottom: -1px;
  left: 0;
  right: 0;
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--border-soft), var(--brand), var(--border-soft), transparent);
  opacity: 0.5;
}

.header-left {
  display: flex;
  align-items: center;
  flex: 1;
  min-width: 0;
  height: 100%;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}

/* ---------- Brand ---------- */
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
  margin-right: 28px;
  height: 100%;
  position: relative;
}

.brand::after {
  content: '';
  position: absolute;
  right: -14px;
  top: 50%;
  transform: translateY(-50%);
  width: 1px;
  height: 20px;
  background: var(--border-strong);
}

.brand-name {
  font-family: 'Chakra Petch', 'IBM Plex Mono', monospace;
  font-size: 16px;
  font-weight: 600;
  color: var(--fg);
  letter-spacing: 2px;
}

.brand-tag {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 9px;
  font-weight: 500;
  letter-spacing: 1.5px;
  color: var(--brand);
  opacity: 0.6;
  padding: 2px 6px;
  border: 1px solid var(--border-soft);
  border-radius: 2px;
}

/* ---------- Top Nav ---------- */
.top-nav {
  display: flex;
  align-items: center;
  gap: 0;
  height: 100%;
  overflow-x: auto;
  overflow-y: visible;
  scrollbar-width: none;
}

.top-nav::-webkit-scrollbar {
  display: none;
}

.nav-item-wrapper {
  position: relative;
  height: 100%;
  display: flex;
  align-items: center;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 0 14px;
  height: 100%;
  cursor: pointer;
  color: var(--fg-mute);
  font-family: 'IBM Plex Mono', monospace;
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.5px;
  white-space: nowrap;
  transition: all 0.15s ease;
  user-select: none;
  position: relative;
}

.nav-item::before {
  content: '';
  position: absolute;
  bottom: 0;
  left: 50%;
  transform: translateX(-50%);
  width: 0;
  height: 2px;
  background: var(--brand);
  transition: width 0.2s ease;
}

.nav-item:hover {
  color: var(--fg);
  background: rgba(0, 229, 255, 0.03);
}

.nav-item:hover::before {
  width: 24px;
}

.nav-item.active {
  color: var(--brand);
  background: rgba(0, 229, 255, 0.06);
}

.nav-item.active::before {
  width: 32px;
  box-shadow: 0 0 8px var(--border-soft);
}

.nav-item.has-children {
  padding-right: 10px;
}

.nav-icon {
  font-size: 14px;
  line-height: 1;
  opacity: 0.7;
}

.nav-item.active .nav-icon {
  opacity: 1;
}

.nav-name {
  line-height: 1;
}

.nav-arrow {
  display: flex;
  align-items: center;
  opacity: 0.4;
  transition: transform 0.2s ease, opacity 0.15s ease;
}

.nav-arrow.open {
  transform: rotate(180deg);
  opacity: 0.9;
}

/* ---------- Panel (expandable dropdown) ---------- */
.nav-panel {
  position: absolute;
  top: 100%;
  left: 0;
  min-width: 220px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 0 0 6px 6px;
  box-shadow:
    0 8px 32px rgba(0, 0, 0, 0.5),
    0 0 0 1px rgba(0, 229, 255, 0.05),
    inset 0 1px 0 rgba(255, 255, 255, 0.03);
  overflow: hidden;
  z-index: 200;
}

.panel-divider {
  height: 1px;
  background: linear-gradient(90deg, var(--border-soft), transparent);
  opacity: 0.4;
}

.panel-grid {
  padding: 6px 0;
}

.panel-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 16px;
  cursor: pointer;
  color: var(--fg-mute);
  font-family: 'IBM Plex Mono', monospace;
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.3px;
  transition: all 0.12s ease;
  white-space: nowrap;
  position: relative;
}

.panel-item:hover {
  color: var(--fg);
  background: rgba(0, 229, 255, 0.04);
}

.panel-item.active {
  color: var(--brand);
  background: rgba(0, 229, 255, 0.08);
}

.panel-item.active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 2px;
  height: 60%;
  background: var(--brand);
  box-shadow: 0 0 6px var(--border-soft);
}

.panel-icon {
  font-size: 13px;
  line-height: 1;
  width: 18px;
  text-align: center;
  flex-shrink: 0;
  opacity: 0.6;
}

.panel-item:hover .panel-icon,
.panel-item.active .panel-icon {
  opacity: 1;
}

.panel-label {
  flex: 1;
}

.panel-corner {
  position: absolute;
  top: 3px;
  right: 3px;
  width: 5px;
  height: 5px;
  border-top: 1px solid var(--border-soft);
  border-right: 1px solid var(--border-soft);
  opacity: 0;
  transition: opacity 0.15s ease;
}

.panel-item:hover .panel-corner,
.panel-item.active .panel-corner {
  opacity: 0.6;
}

/* ---------- Transition ---------- */
.panel-enter-active {
  transition: all 0.18s cubic-bezier(0.16, 1, 0.3, 1);
}

.panel-leave-active {
  transition: all 0.12s ease-in;
}

.panel-enter-from {
  opacity: 0;
  transform: translateY(-6px) scale(0.98);
}

.panel-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

/* ---------- Header Right ---------- */
.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--brand);
  box-shadow: 0 0 6px var(--brand), 0 0 12px var(--border-soft);
  animation: pulse-dot 2s ease-in-out infinite;
}

@keyframes pulse-dot {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

.username {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 12px;
  color: var(--fg-mute);
  letter-spacing: 0.3px;
}

.role-badge {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 9px;
  font-weight: 600;
  letter-spacing: 1px;
  color: var(--warn);
  padding: 2px 7px;
  border: 1px solid rgba(255, 176, 32, 0.25);
  border-radius: 2px;
  background: rgba(255, 176, 32, 0.06);
}

.btn-logout {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 4px;
  border: 1px solid var(--border-strong);
  background: transparent;
  color: var(--fg-dim);
  cursor: pointer;
  transition: all 0.15s ease;
  padding: 0;
}

.btn-logout:hover {
  color: var(--danger);
  border-color: rgba(248, 82, 82, 0.3);
  background: rgba(248, 82, 82, 0.06);
}

/* ---------- Content ---------- */
.app-main {
  flex: 1;
  overflow-y: auto;
  background: var(--canvas);
}

/* ====================================================================
   Mobile — max-width 768px
   ==================================================================== */

@media (max-width: 768px) {
  .app-header {
    padding: 0 12px;
  }

  .brand {
    margin-right: 0;
    gap: 8px;
  }

  .brand::after {
    display: none;
  }

  .brand-name {
    font-size: 14px;
    letter-spacing: 1px;
  }

  .brand-tag {
    display: none;
  }

  /* --- Hamburger --- */
  .hamburger {
    display: flex;
    flex-direction: column;
    justify-content: center;
    align-items: center;
    gap: 4px;
    width: 36px;
    height: 36px;
    background: transparent;
    border: 1px solid var(--border);
    border-radius: var(--radius-xs);
    cursor: pointer;
    padding: 0;
    margin-left: 10px;
    flex-shrink: 0;
    z-index: 300;
  }

  .hamburger-line {
    display: block;
    width: 18px;
    height: 2px;
    background: var(--fg-mute);
    border-radius: 1px;
    transition: all 0.2s var(--ease-out);
  }

  .hamburger-line.open:nth-child(1) {
    transform: translateY(6px) rotate(45deg);
  }

  .hamburger-line.open:nth-child(2) {
    opacity: 0;
  }

  .hamburger-line.open:nth-child(3) {
    transform: translateY(-6px) rotate(-45deg);
  }

  /* --- Hide desktop nav --- */
  .top-nav {
    display: none;
  }

  /* --- Hide theme & logout from header --- */
  .header-right .btn-logout {
    display: none;
  }

  .username {
    max-width: 80px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .header-right {
    gap: 8px;
  }

  /* --- Drawer backdrop --- */
  .drawer-backdrop {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.5);
    z-index: 299;
  }

  /* --- Mobile nav drawer --- */
  .mobile-nav-drawer {
    position: fixed;
    top: 0;
    left: 0;
    bottom: 0;
    width: 280px;
    max-width: 85vw;
    background: var(--surface);
    border-right: 1px solid var(--border);
    z-index: 300;
    display: flex;
    flex-direction: column;
    overflow-y: auto;
    box-shadow: 8px 0 32px rgba(0, 0, 0, 0.5);
  }

  .drawer-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 14px 16px;
    border-bottom: 1px solid var(--border);
    flex-shrink: 0;
  }

  .drawer-title {
    font-family: var(--font-mono);
    font-size: 11px;
    letter-spacing: 0.1em;
    color: var(--ink-mute);
  }

  .drawer-close {
    background: none;
    border: none;
    color: var(--fg-dim);
    font-size: 18px;
    cursor: pointer;
    padding: 4px 8px;
    border-radius: 4px;
    transition: color 0.15s ease;
  }

  .drawer-close:hover {
    color: var(--fg);
  }

  .drawer-nav {
    flex: 1;
    padding: 8px 0;
    overflow-y: auto;
  }

  .drawer-nav-parent {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 13px 20px;
    font-family: var(--font-mono);
    font-size: 13px;
    font-weight: 600;
    color: var(--fg-mute);
    cursor: pointer;
    letter-spacing: 0.3px;
    border-left: 3px solid transparent;
    transition: all 0.12s ease;
  }

  .drawer-nav-parent:hover,
  .drawer-nav-parent.active {
    color: var(--brand);
    background: var(--brand-soft);
    border-left-color: var(--brand);
  }

  .drawer-nav-child {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 10px 20px 10px 36px;
    font-size: 13px;
    color: var(--ink-2);
    cursor: pointer;
    transition: all 0.12s ease;
  }

  .drawer-nav-child:hover,
  .drawer-nav-child.active {
    color: var(--brand);
    background: var(--brand-soft);
  }

  .drawer-child-dot {
    width: 4px;
    height: 4px;
    border-radius: 50%;
    background: var(--border-strong);
    flex-shrink: 0;
  }

  .drawer-nav-child.active .drawer-child-dot {
    background: var(--brand);
  }

  .drawer-chevron {
    font-size: 16px;
    opacity: 0.4;
  }

  .drawer-footer {
    border-top: 1px solid var(--border);
    padding: 16px 20px;
    display: flex;
    flex-direction: column;
    gap: 12px;
    flex-shrink: 0;
  }

  .drawer-user {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .drawer-status-dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--brand);
    box-shadow: 0 0 6px var(--brand);
    flex-shrink: 0;
  }

  .drawer-actions {
    display: flex;
    gap: 8px;
  }

  .drawer-actions .btn-ghost {
    flex: 1;
    justify-content: center;
    font-size: 12px;
    padding: 6px 12px;
  }

  .drawer-actions .btn-mini-danger {
    padding: 6px 16px;
    font-size: 11px;
  }

  .btn-sm {
    padding: 4px 10px;
    font-size: 12px;
  }
}

/* --- Drawer transition --- */
.drawer-enter-active {
  transition: transform 0.25s var(--ease-out);
}

.drawer-leave-active {
  transition: transform 0.2s ease-in;
}

.drawer-enter-from,
.drawer-leave-to {
  transform: translateX(-100%);
}

/* --- Backdrop fade transition --- */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
