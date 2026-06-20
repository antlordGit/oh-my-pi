<script setup lang="ts">
import { ref } from 'vue'
import { useMenuStore } from '@/stores/menu'
import AppMenuItem from './AppMenuItem.vue'

const menuStore = useMenuStore()
// 各菜单节点的展开状态（id → 是否展开），默认 undefined 视为展开
const expanded = ref<Record<number, boolean>>({})
</script>

<template>
  <aside class="sidebar">
    <nav class="sidebar-nav">
      <AppMenuItem
        v-for="menu in menuStore.topMenus"
        :key="menu.id"
        :menu="menu"
        :depth="0"
        v-model:expanded="expanded"
      />
    </nav>
  </aside>
</template>

<style scoped>
.sidebar {
  width: 220px;
  min-width: 220px;
  background: var(--surface);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  height: 100%;
}
.sidebar-nav {
  flex: 1;
  padding: 12px 0;
  overflow-y: auto;
}
</style>