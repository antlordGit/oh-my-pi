<script setup lang="ts">
import { onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useMenuStore } from '@/stores/menu'
import AppSidebar from './AppSidebar.vue'

const router = useRouter()
const auth = useAuthStore()
const menuStore = useMenuStore()

onMounted(() => {
  if (auth.token && !menuStore.loaded) {
    menuStore.loadMenus()
  }
})

// 登录后加载菜单
watch(() => auth.token, (token) => {
  if (token) {
    menuStore.loadMenus()
  } else {
    menuStore.clearMenus()
  }
})

function logout() {
  auth.logout()
  router.replace('/login')
}
</script>

<template>
  <div class="app-layout">
    <!-- 顶部栏 -->
    <header class="app-header">
      <div class="brand">
        <svg viewBox="0 0 24 24" width="22" height="22" fill="none">
          <path d="M4 20 L12 4 L20 20 L16 20 L12 12 L8 20 Z" fill="#165DFF"/>
        </svg>
        <span class="brand-name">OMP</span>
      </div>
      <div class="header-actions">
        <span class="username">{{ auth.username }}</span>
        <span v-if="auth.isAdmin" class="tag" style="margin-left:8px">{{ auth.isSuperAdmin ? '超级管理员' : '管理员' }}</span>
        <button class="btn-ghost" @click="logout">登出</button>
      </div>
    </header>

    <!-- 主体区域 -->
    <div class="app-body">
      <AppSidebar />
      <main class="app-main">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped>
.app-layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
}

.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  height: 56px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.brand-name {
  font-size: 18px;
  font-weight: 700;
  color: var(--ink);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.username {
  font-size: 13px;
  color: var(--ink-2);
}

.btn-ghost {
  padding: 6px 16px;
  border-radius: var(--radius-pill);
  border: 1px solid var(--border);
  background: transparent;
  color: var(--ink-2);
  font-size: 13px;
  cursor: pointer;
  transition: all var(--dur-fast);
}

.btn-ghost:hover {
  background: var(--surface-hover);
  color: var(--brand);
}

.app-body {
  display: flex;
  flex: 1;
  overflow: hidden;
}

.app-main {
  flex: 1;
  overflow-y: auto;
  background: var(--surface-soft);
}
</style>