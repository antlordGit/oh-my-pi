<script setup lang="ts">
import { ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { success, error } from '@/utils/toast'

const auth = useAuthStore()
const theme = useThemeStore()

const username = ref('')
const password = ref('')
const loading = ref(false)

async function submit() {
  if (!username.value) {
    error('请输入用户名')
    return
  }
  if (!password.value) {
    error('请输入密码')
    return
  }
  loading.value = true
  try {
    await auth.login(username.value, password.value)
    success('欢迎回来')
    uni.reLaunch({ url: '/pages/sessions/sessions' })
  } catch (e: any) {
    error(e?.message || e?.data?.error || '凭证无效')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <view :class="['page', theme.themeClass()]">
    <!-- 顶部占位（保持布局一致） -->
    <view class="topbar">
      <view class="topbar-spacer" />
    </view>

    <!-- 中部 logo / 标题 -->
    <view class="hero">
      <view class="brand-circle">
        <image class="brand-logo" src="/static/logo.svg" mode="aspectFit" />
      </view>
      <text class="brand-title">OMP</text>
      <text class="brand-sub">AI 编码工作站</text>
    </view>

    <!-- 输入区 -->
    <view class="form">
      <view class="form-row">
        <text class="form-label">账号</text>
        <input
          v-model="username"
          class="form-input"
          placeholder="请输入账号"
          placeholder-class="form-ph"
        />
      </view>
      <view class="form-row">
        <text class="form-label">密码</text>
        <input
          v-model="password"
          class="form-input"
          type="password"
          placeholder="请输入密码"
          placeholder-class="form-ph"
          @confirm="submit"
        />
      </view>
    </view>

    <!-- 主按钮 -->
    <button
      class="btn-primary"
      :class="{ loading }"
      :disabled="loading"
      @click="submit"
    >
      {{ loading ? '登 录 中…' : '登 录' }}
    </button>

    <text class="hint">默认账号 admin / admin</text>

    <!-- 底部版权 -->
    <view class="footer">
      <text class="footer-text">OMP Consortium · v0.1.0</text>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  background: var(--canvas);
  padding: 0 32px;
  padding-top: calc(env(safe-area-inset-top, 0) + 20px);
}

.topbar {
  align-self: stretch;
  display: flex;
  justify-content: flex-end;
  height: 44px;
  align-items: center;
}

.topbar-spacer {
  width: 36px;
  height: 36px;
}

// ---- Hero ----
.hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  padding: 60px 0 56px;
}

.brand-circle {
  width: 76px;
  height: 76px;
  border-radius: 18px;
  background: var(--brand);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 8px 20px rgba(7, 193, 96, 0.22);
}

.brand-logo {
  width: 40px;
  height: 40px;
  filter: brightness(0) invert(1);
}

.brand-title {
  font-size: 28px;
  font-weight: 600;
  color: var(--ink);
  letter-spacing: 2px;
  font-family: var(--font-display);
}

.brand-sub {
  font-size: 14px;
  color: var(--ink-mute);
  letter-spacing: 0.5px;
}

// ---- Form (微信下划线表单) ----
.form {
  align-self: stretch;
  background: var(--surface);
  border-radius: 10px;
  overflow: hidden;
  margin-top: 8px;
}

.form-row {
  display: flex;
  align-items: center;
  height: 54px;
  padding: 0 16px;
  position: relative;
}

.form-row + .form-row::before {
  content: '';
  position: absolute;
  top: 0;
  left: 76px;
  right: 16px;
  height: 1px;
  background: var(--separator);
  transform: scaleY(0.5);
}

.form-label {
  width: 60px;
  font-size: 16px;
  color: var(--ink);
  flex-shrink: 0;
}

.form-input {
  flex: 1;
  height: 100%;
  font-size: 16px;
  color: var(--ink);
  background: transparent;
}

.form-ph {
  color: var(--ink-faint);
}

// ---- Primary CTA ----
.btn-primary {
  align-self: stretch;
  margin-top: 32px;
  height: 48px;
  border-radius: 8px;
  background: var(--brand);
  color: #fff;
  font-size: 17px;
  font-weight: 500;
  border: none;
  display: flex;
  align-items: center;
  justify-content: center;
  letter-spacing: 4px;
  transition: background 180ms ease;
}

.btn-primary:active {
  background: var(--brand-pressed);
}

.btn-primary.loading {
  opacity: 0.6;
}

.btn-primary::after {
  border: none;
}

.hint {
  margin-top: 16px;
  font-size: 12px;
  color: var(--ink-mute);
}

.footer {
  margin-top: auto;
  padding-bottom: calc(env(safe-area-inset-bottom) + 16px);
  padding-top: 24px;
}

.footer-text {
  font-size: 11px;
  color: var(--ink-faint);
  letter-spacing: 0.4px;
}
</style>
