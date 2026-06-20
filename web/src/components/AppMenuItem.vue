<script setup lang="ts">
import { useRouter, useRoute } from 'vue-router'
import type { MenuInfo } from '@/api/system'

const props = defineProps<{ menu: MenuInfo; depth: number }>()
const router = useRouter()
const route = useRoute()

// 该节点的可见子菜单（仅 menu 类型且启用）
function visibleChildren(menu: MenuInfo): MenuInfo[] {
  if (!menu.children) return []
  return menu.children
    .filter(m => m.menuType === 'menu' && m.enabled)
    .sort((a, b) => a.sortOrder - b.sortOrder)
}

const children = visibleChildren(props.menu)
const hasChildren = children.length > 0

// 展开状态：默认展开（顶层和包含当前路由的分组）
const expandedMap = defineModel<Record<number, boolean>>('expanded', { required: true })

function isExpanded(id: number): boolean {
  return expandedMap.value[id] ?? true
}

function toggle(id: number) {
  expandedMap.value = { ...expandedMap.value, [id]: !isExpanded(id) }
}

function isActive(menu: MenuInfo): boolean {
  if (!menu.path) return false
  return route.path === menu.path || route.path.startsWith(menu.path + '/')
}

function onClick() {
  if (hasChildren) {
    toggle(props.menu.id)
  } else if (props.menu.path) {
    router.push(props.menu.path)
  }
}
</script>

<template>
  <div class="menu-node">
    <div
      class="menu-item"
      :class="{ active: !hasChildren && isActive(menu), 'is-group': hasChildren }"
      :style="{ paddingLeft: 16 + depth * 16 + 'px' }"
      @click="onClick"
    >
      <span v-if="depth === 0" class="menu-icon">{{ menu.icon || '📁' }}</span>
      <span class="menu-name">{{ menu.menuName }}</span>
      <span v-if="hasChildren" class="menu-arrow" :class="{ open: isExpanded(menu.id) }">▶</span>
    </div>
    <div v-if="hasChildren" v-show="isExpanded(menu.id)" class="menu-children">
      <AppMenuItem
        v-for="child in children"
        :key="child.id"
        :menu="child"
        :depth="depth + 1"
        v-model:expanded="expandedMap"
      />
    </div>
  </div>
</template>

<style scoped>
.menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  cursor: pointer;
  color: var(--ink-2);
  font-size: 14px;
  font-weight: 500;
  transition: background var(--dur-fast), color var(--dur-fast);
  user-select: none;
}
.menu-item:hover {
  background: var(--surface-hover);
  color: var(--brand);
}
.menu-item.active {
  background: var(--brand-soft);
  color: var(--brand);
}
.menu-icon {
  font-size: 16px;
  width: 20px;
  text-align: center;
}
.menu-name {
  flex: 1;
}
.menu-arrow {
  font-size: 10px;
  color: var(--ink-mute);
  transition: transform 0.2s ease;
}
.menu-arrow.open {
  transform: rotate(90deg);
}
</style>