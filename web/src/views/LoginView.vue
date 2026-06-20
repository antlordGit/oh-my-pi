<script setup lang="ts">
import { ref } from 'vue'
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
    <!-- Left: brand + big editorial typography -->
    <section class="col col-left">
      <header class="brand fade-up">
        <span class="brand-mark">
          <svg viewBox="0 0 24 24" width="24" height="24" fill="none">
            <path d="M4 20 L12 4 L20 20 L16 20 L12 12 L8 20 Z" fill="#165DFF"/>
          </svg>
        </span>
        <span class="brand-name">OMP</span>
      </header>

      <div class="hero fade-up" style="animation-delay:100ms">
        <h1 class="hero-title display">
          进入你的<br />
          <strong>编码工作区</strong>
        </h1>
        <p class="hero-sub">
          AI 代理在受信沙箱中执行编辑、构建与诊断。
        </p>
      </div>

      <footer class="colophon fade-up" style="animation-delay:300ms">
        <span class="serial">OMP Consortium</span>
        <span class="dotline-fill"></span>
        <span class="serial">v15.12.6</span>
      </footer>
    </section>

    <!-- Right: Double-Bezel login island -->
    <section class="col col-right">
      <div class="form-shell fade-up" style="animation-delay:200ms">
        <div class="form-island">
          <div class="form-core">
            <header class="form-head">
              <span class="eyebrow">Credentials</span>
              <h2 class="form-title display">
                出示<strong>凭证</strong>
              </h2>
              <p class="form-dek">
                默认 <code>admin</code> / <code>admin</code>
              </p>
            </header>

            <form @submit.prevent="submit" class="form-body">
              <label class="field">
                <span class="field-label serial">操作员</span>
                <input
                  v-model="username"
                  type="text"
                  autofocus
                  autocomplete="username"
                  spellcheck="false"
                  placeholder="username"
                  class="field-raw"
                />
              </label>

              <label class="field">
                <span class="field-label serial">通行密钥</span>
                <input
                  v-model="password"
                  type="password"
                  autocomplete="current-password"
                  placeholder="········"
                  class="field-raw"
                  @keyup.enter="submit"
                />
              </label>

              <button class="btn-primary login-btn" :disabled="loading" type="submit">
                <span class="label">{{ loading ? '正在验证…' : '进入工作室' }}</span>
              </button>
            </form>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login {
  min-height: 100dvh;
  display: grid;
  grid-template-columns: 1.1fr 0.9fr;
  background: var(--canvas);
  position: relative;
  overflow: hidden;
}

/* ====================================================================
   Left — brand + editorial typography
   ==================================================================== */
.col {
  position: relative;
  display: flex;
  flex-direction: column;
  padding: 56px 64px;
  z-index: 1;
}
.col-left {
  gap: 48px;
  justify-content: space-between;
  background:
    radial-gradient(circle at 20% 0%, rgba(22, 93, 255, 0.05), transparent 55%);
}
.brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}
.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px; height: 36px;
  background: rgba(22, 93, 255, 0.08);
  border-radius: 8px;
}
.brand-name {
  font-family: var(--font-display);
  font-weight: 700;
  font-size: 20px;
  letter-spacing: -0.01em;
  color: var(--ink);
}

.hero {
  display: flex;
  flex-direction: column;
  gap: 20px;
  max-width: 520px;
  margin-top: auto;
  margin-bottom: auto;
}
.hero-title {
  font-size: clamp(48px, 5vw, 72px);
  line-height: 1.06;
  font-weight: 800;
  letter-spacing: -0.025em;
  margin: 0;
}
.hero-title strong {
  color: var(--brand);
  font-weight: 800;
}
.hero-sub {
  font-size: 16px;
  line-height: 1.6;
  color: var(--ink-2);
  margin: 0;
  max-width: 400px;
}

.colophon {
  display: flex;
  align-items: center;
  gap: 14px;
  max-width: 520px;
  padding-top: 20px;
  border-top: 1px solid var(--border);
}

/* ====================================================================
   Right — Double-Bezel login island
   ==================================================================== */
.col-right {
  align-items: center;
  justify-content: center;
}

.form-shell { width: 100%; max-width: 440px; }

.form-island {
  /* outer shell */
  padding: 1.5px;
  border-radius: var(--radius-card);
  background: linear-gradient(180deg,
    rgba(22, 93, 255, 0.12) 0%,
    rgba(22, 93, 255, 0.04) 100%);
  box-shadow: var(--shadow-hover);
}

.form-core {
  /* inner core */
  background: var(--surface);
  border-radius: calc(var(--radius-card) - 1.5px);
  padding: 44px 40px 36px;
  box-shadow: inset 0 1px 0 rgba(255,255,255,0.6),
              inset 0 -1px 0 rgba(0,0,0,0.04);
}

.form-head {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 36px;
}

.form-title {
  font-size: 32px;
  font-weight: 800;
  letter-spacing: -0.025em;
}
.form-title strong { color: var(--brand); font-weight: 800; }

.form-dek {
  font-size: 13px;
  color: var(--ink-mute);
  margin: 0;
}
.form-dek code {
  font-family: var(--font-mono);
  background: var(--brand-soft);
  color: var(--brand);
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 11px;
}

.form-body {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.field { display: flex; flex-direction: column; gap: 6px; }
.field-label { padding-left: 2px; }

.login-btn {
  margin-top: 12px;
  height: 52px;
  width: 100%;
  justify-content: center;
  font-size: 12px;
}

/* ====================================================================
   Eyebrow tag
   ==================================================================== */
.eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 5px 12px;
  border-radius: var(--radius-pill);
  background: var(--brand-soft);
  color: var(--brand);
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  align-self: flex-start;
}
.eyebrow::before {
  content: '';
  width: 5px; height: 5px;
  border-radius: 50%;
  background: var(--brand);
}

/* ====================================================================
   Mobile collapse
   ==================================================================== */
@media (max-width: 768px) {
  .login { grid-template-columns: 1fr; grid-template-rows: auto 1fr; }
  .col { padding: 40px 24px; }
  .col-left { gap: 28px; justify-content: flex-start; }
  .hero { margin: 0; }
  .hero-title { font-size: 40px; }
  .form-core { padding: 32px 24px; }
}
</style>