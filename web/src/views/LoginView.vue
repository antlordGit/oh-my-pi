<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useMessage } from 'naive-ui'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const msg = useMessage()

const username = ref('')
const password = ref('')
const loading = ref(false)

const today = computed(() => {
  const d = new Date()
  return `${d.getFullYear()}.${String(d.getMonth() + 1).padStart(2, '0')}.${String(d.getDate()).padStart(2, '0')}`
})

async function submit() {
  if (!username.value || !password.value) { msg.warning('请输入凭证'); return }
  loading.value = true
  try {
    await auth.login(username.value, password.value)
    msg.success('欢迎回来')
    router.replace((route.query.redirect as string) || '/sessions')
  } catch (e: any) {
    msg.error(e?.response?.data?.error || '凭证无效')
  } finally { loading.value = false }
}
</script>

<template>
  <div class="login">
    <!-- Decorative gradient blobs -->
    <div class="blob blob-1"></div>
    <div class="blob blob-2"></div>

    <div class="content">
      <!-- Hero typography -->
      <header class="hero fade-up">
        <div class="hero-tag">AI · CODING · WORKSTATION</div>
        <h1 class="hero-title serif">
          让你的代码<br />
          <em>自然而然</em>
        </h1>
        <p class="hero-dek">
          进入你的专属 AI 编码工作区。代理在受信沙箱中执行编辑、构建与诊断，一切可控。
        </p>
      </header>

      <!-- Login form -->
      <div class="form-card fade-up" style="animation-delay: 120ms">
        <form @submit.prevent="submit">
          <div class="field">
            <input
              v-model="username"
              type="text"
              autofocus
              autocomplete="username"
              spellcheck="false"
              placeholder="操作员标识"
            />
          </div>
          <div class="field">
            <input
              v-model="password"
              type="password"
              autocomplete="current-password"
              placeholder="通行密钥"
              @keyup.enter="submit"
            />
          </div>
          <button class="submit" :disabled="loading" type="submit">
            <span>{{ loading ? '正在验证…' : '进入工作室' }}</span>
            <svg width="20" height="20" viewBox="0 0 20 20" fill="none">
              <path d="M4 10h12M10 4l6 6-6 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </button>
        </form>
      </div>

      <p class="footer-note fade-up" style="animation-delay: 200ms">
        默认凭证 <code>admin</code> / <code>admin</code> &nbsp;·&nbsp; {{ today }} &nbsp;·&nbsp; OMP CONSORTIUM
      </p>
    </div>
  </div>
</template>

<style scoped>
.login {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  overflow: hidden;
  background: var(--bg-base);
}

/* Gradient blobs for energy */
.blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(120px);
  opacity: 0.35;
  pointer-events: none;
}
.blob-1 {
  width: 600px; height: 600px;
  background: radial-gradient(circle, rgba(224, 78, 60, 0.18) 0%, transparent 70%);
  top: -15%;
  right: -10%;
}
.blob-2 {
  width: 400px; height: 400px;
  background: radial-gradient(circle, rgba(212, 133, 30, 0.12) 0%, transparent 70%);
  bottom: -10%;
  left: -5%;
}

.content {
  width: 100%;
  max-width: 440px;
  padding: 32px;
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 36px;
}

/* Hero */
.hero {
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}
.hero-tag {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.25em;
  color: var(--accent);
  text-transform: uppercase;
  font-weight: 600;
  background: var(--accent-soft);
  padding: 6px 16px;
  border-radius: 20px;
}
.hero-title {
  font-size: 52px;
  line-height: 0.98;
  font-weight: 320;
  font-variation-settings: "opsz" 144, "SOFT" 30, "WONK" 1;
  color: var(--text);
  letter-spacing: -0.025em;
}
.hero-title em {
  font-style: italic;
  color: var(--accent);
  font-variation-settings: "opsz" 144, "SOFT" 100, "WONK" 1;
}
.hero-dek {
  font-size: 16px;
  line-height: 1.6;
  color: var(--text-secondary);
  max-width: 360px;
}

/* Form card */
.form-card {
  width: 100%;
  background: var(--bg-raised);
  border: 1px solid var(--border);
  border-radius: 16px;
  box-shadow: var(--shadow-float);
  padding: 32px;
}
.form-card form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.field input {
  width: 100%;
  background: var(--bg-base);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 14px 18px;
  font-family: var(--font-mono);
  font-size: 15px;
  color: var(--text);
  caret-color: var(--accent);
  outline: 0;
  transition: border-color 160ms ease, box-shadow 160ms ease;
}
.field input::placeholder { color: var(--text-faint); font-size: 13px; }
.field input:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-soft);
}

.submit {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 16px 24px;
  background: var(--accent);
  color: #fff;
  border: 0;
  border-radius: 10px;
  cursor: pointer;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: -0.01em;
  transition: all 180ms ease;
  margin-top: 4px;
}
.submit:hover:not(:disabled) {
  background: var(--accent-glow);
  box-shadow: 0 8px 32px rgba(224, 78, 60, 0.3);
  transform: translateY(-1px);
}
.submit:disabled {
  background: var(--border);
  color: var(--text-faint);
  cursor: not-allowed;
  transform: none;
}

.footer-note {
  font-size: 13px;
  color: var(--text-muted);
  text-align: center;
}
.footer-note code {
  font-family: var(--font-mono);
  font-size: 12px;
  background: var(--bg-sunken);
  padding: 2px 6px;
  border-radius: 4px;
  color: var(--text-secondary);
}
</style>
