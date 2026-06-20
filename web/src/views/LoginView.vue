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
    <!-- Top nav (Volcengine style) -->
    <header class="topbar fade-up">
      <div class="brand">
        <span class="brand-mark">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none">
            <path d="M4 20 L12 4 L20 20 L16 20 L12 12 L8 20 Z" fill="#165DFF"/>
          </svg>
        </span>
        <span class="brand-name">OMP</span>
      </div>

      <nav class="nav-links">
        <a class="nav-link active">工作台</a>
        <a class="nav-link">模型</a>
        <a class="nav-link">解决方案</a>
        <a class="nav-link">定价</a>
        <a class="nav-link">文档</a>
      </nav>

      <div class="nav-actions">
        <button class="btn-ghost">登入</button>
        <button class="btn-primary">免费开始使用</button>
      </div>
    </header>

    <main class="hero">
      <div class="hero-text fade-up" style="animation-delay:80ms">
        <span class="tag">AI · 编码 · 工作站</span>
        <h1 class="hero-title">
          方舟 <span class="title-accent">Agent</span><br />
          <span class="title-plan">Plan</span>
        </h1>
        <p class="hero-sub">
          超全模态模型 × Harness 升级，<br class="sub-break" />
          最新支持 MiniMax-M3 与 GLM-5.2，限时 9.9 元起，加量不加价。
        </p>
        <div class="hero-cta">
          <button class="btn-primary" @click="submit">
            {{ loading ? '正在验证…' : '立即开始使用' }}
          </button>
          <button class="btn-outline" @click="$router.push('/sessions')">
            查看使用指南
          </button>
        </div>
      </div>

      <div class="hero-showcase fade-up" style="animation-delay:200ms">
        <div class="showcase-card">
          <div class="showcase-grid">
            <div class="showcase-item">
              <span class="serial">01</span>
              <p class="sc-text">已完成热点信息搜集，接下来进行宣传图<strong>文物料</strong>制作</p>
              <div class="sc-tags">
                <span class="sc-tag">Doubao-Seed-2.0-pro</span>
                <span class="sc-tag">Doubao-Seedance-2.0</span>
              </div>
              <div class="sc-line dim">已完成视频生成 ▶ 今日热点</div>
            </div>
            <div class="showcase-item">
              <span class="serial">02</span>
              <p class="sc-text">素材准备完成，已读取到素材位置，准备运行脚本进行<strong>自动化</strong>发布</p>
              <div class="sc-tags">
                <span class="sc-tag blue">Doubao-Seed-2.0-Code</span>
              </div>
              <div class="sc-line dim">已读取脚本位置，检查脚本状态</div>
            </div>
            <div class="showcase-pill">
              <span class="serial accent">每天早上9点，针对当天的行业热点，生成一套宣传物料并发布到社交媒体中</span>
              <span class="pill-arrow">↑</span>
            </div>
          </div>

          <!-- floating model chips -->
          <div class="model-chip chip-1">
            <span class="chip-dot" style="background:#165DFF"></span>
            <span class="chip-text">Doubao-Seed-2.0-pro</span>
          </div>
          <div class="model-chip chip-2">
            <span class="chip-dot" style="background:#7B7BFF"></span>
            <span class="chip-text">GLM-5.2</span>
          </div>
          <div class="model-chip chip-3">
            <span class="chip-dot" style="background:#FF7D00"></span>
            <span class="chip-text">MiniMax-M2.7</span>
          </div>
          <div class="model-chip chip-4">
            <span class="chip-dot" style="background:#00B42A"></span>
            <span class="chip-text">Kimi-K2.6</span>
          </div>
          <div class="model-chip chip-5">
            <span class="chip-dot" style="background:#165DFF"></span>
            <span class="chip-text">DeepSeek-V3.2</span>
          </div>
        </div>
      </div>
    </main>

    <!-- Promo strip -->
    <section class="promo fade-up" style="animation-delay:320ms">
      <h2 class="promo-title">限时 9.9 元起 × 加量不加价</h2>
      <p class="promo-dek">
        套餐用量透明化，查看 <a>套餐详情</a>，Small 和 Medium 套餐限时折扣，名额有限，先到先得，详见 <a>折扣规则</a>，GLM-5.2 等热门模型限时加量 2.5 倍，详见 <a>加量规则</a>
      </p>
    </section>

    <!-- Footer -->
    <footer class="footer fade-up" style="animation-delay:400ms">
      <span class="serial">© OMP Consortium 2026 · v15.12.6</span>
      <span class="dotline-fill"></span>
      <span class="serial">登录后即表示同意 <a>服务条款</a> 与 <a>隐私政策</a></span>
    </footer>

    <!-- Hidden form (carries login state, triggered by top-right "登入") -->
    <div class="login-card-wrap fade-up" style="animation-delay:160ms">
      <div class="login-card">
        <h3 class="card-title">操作员登录</h3>
        <p class="card-sub">默认凭证 <code>admin</code> · <code>admin</code></p>
        <form @submit.prevent="submit" class="card-form">
          <label class="field">
            <span class="field-label serial">操作员 · Operator</span>
            <input v-model="username" type="text" autofocus autocomplete="username" spellcheck="false" placeholder="who are you?" class="field-raw" />
          </label>
          <label class="field">
            <span class="field-label serial">通行密钥 · Passphrase</span>
            <input v-model="password" type="password" autocomplete="current-password" placeholder="••••••••••••" class="field-raw" @keyup.enter="submit" />
          </label>
        </form>
        <p class="card-foot">点击「立即开始使用」直接进入。</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login {
  min-height: 100dvh;
  background: var(--canvas);
  position: relative;
  display: flex;
  flex-direction: column;
  padding: 0 64px 48px;
}

/* ====================================================================
   Topbar — Volcengine split nav
   ==================================================================== */
.topbar {
  display: flex;
  align-items: center;
  gap: 32px;
  padding: 20px 0;
  position: sticky;
  top: 0;
  background: rgba(255, 255, 255, 0.86);
  backdrop-filter: saturate(180%) blur(16px);
  -webkit-backdrop-filter: saturate(180%) blur(16px);
  z-index: 10;
}
.brand {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  background: rgba(22, 93, 255, 0.10);
  border-radius: 8px;
}
.brand-name {
  font-size: 18px;
  font-weight: 700;
  color: var(--ink);
  letter-spacing: -0.01em;
}

.nav-links { display: inline-flex; align-items: center; gap: 28px; }
.nav-link {
  font-size: 14px;
  color: var(--ink-2);
  cursor: pointer;
  transition: color var(--dur-fast) var(--ease-out);
}
.nav-link:hover, .nav-link.active { color: var(--brand); }

.nav-actions { margin-left: auto; display: inline-flex; align-items: center; gap: 10px; }

/* ====================================================================
   Hero
   ==================================================================== */
.hero {
  display: grid;
  grid-template-columns: 1fr 1.1fr;
  gap: 56px;
  align-items: center;
  padding: 64px 0 80px;
  position: relative;
  z-index: 1;
}

.hero-text { display: flex; flex-direction: column; gap: 24px; max-width: 560px; }
.hero-title {
  font-family: var(--font-display);
  font-size: clamp(56px, 6vw, 88px);
  font-weight: 800;
  line-height: 1.05;
  letter-spacing: -0.03em;
  color: var(--ink);
  margin: 0;
}
.title-accent { color: var(--brand); }
.title-plan { color: var(--ink); }

.hero-sub {
  font-size: 16px;
  line-height: 1.65;
  color: var(--ink-2);
  margin: 0;
  max-width: 480px;
}
.sub-break { display: none; }

.hero-cta { display: flex; gap: 16px; flex-wrap: wrap; margin-top: 8px; }
.hero-cta .btn-primary, .hero-cta .btn-outline { padding: 12px 32px; font-size: 15px; }

/* ====================================================================
   Showcase — blue→purple gradient panel with floating chips
   ==================================================================== */
.hero-showcase { position: relative; }
.showcase-card {
  position: relative;
  padding: 1px;
  border-radius: var(--radius-card);
  background: linear-gradient(135deg,
    rgba(255, 255, 255, 0.6) 0%,
    rgba(22, 93, 255, 0.18) 50%,
    rgba(123, 123, 255, 0.36) 100%);
  box-shadow: 0 24px 64px rgba(22, 93, 255, 0.18);
  isolation: isolate;
  min-height: 480px;
}
.showcase-card::before {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: linear-gradient(135deg,
    #DCE6FF 0%,
    #C5D0FF 50%,
    #B3B7FF 100%);
  z-index: -1;
}
.showcase-grid {
  padding: 36px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.showcase-item {
  background: rgba(255, 255, 255, 0.78);
  backdrop-filter: blur(12px);
  border: 1px solid rgba(255, 255, 255, 0.5);
  border-radius: var(--radius);
  padding: 16px 20px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  box-shadow: 0 4px 12px rgba(22, 93, 255, 0.08);
}
.sc-text { margin: 0; font-size: 14px; color: var(--ink); line-height: 1.55; }
.sc-text strong { color: var(--brand); font-weight: 600; }
.sc-tags { display: flex; gap: 8px; flex-wrap: wrap; }
.sc-tag {
  display: inline-flex;
  padding: 2px 10px;
  border-radius: var(--radius-pill);
  background: rgba(22, 93, 255, 0.10);
  color: var(--brand);
  font-size: 11px;
  font-weight: 500;
}
.sc-tag.blue { background: rgba(255, 255, 255, 0.6); color: var(--ink-2); border: 1px solid rgba(22, 93, 255, 0.12); }
.sc-line { font-size: 12px; color: var(--ink-mute); }
.sc-line.dim { color: var(--ink-faint); }

.showcase-pill {
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(255, 255, 255, 0.5);
  border-radius: var(--radius);
  padding: 14px 20px;
  display: flex;
  align-items: center;
  gap: 14px;
  box-shadow: 0 6px 16px rgba(22, 93, 255, 0.10);
  margin-top: 4px;
}
.showcase-pill .serial {
  flex: 1;
  font-family: var(--font-display);
  font-size: 13px;
  font-weight: 500;
  letter-spacing: 0.02em;
  text-transform: none;
  color: var(--ink);
}
.pill-arrow {
  width: 28px; height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--brand);
  color: var(--ink-invert);
  border-radius: var(--radius-xs);
  font-size: 14px;
}

/* Floating model chips around the showcase */
.model-chip {
  position: absolute;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 7px 14px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
  font-size: 12px;
  font-weight: 500;
  color: var(--ink);
  box-shadow: 0 6px 18px rgba(22, 93, 255, 0.12);
  white-space: nowrap;
  animation: fade-up var(--dur-slow) var(--ease-out) both;
}
.chip-dot { width: 8px; height: 8px; border-radius: 50%; }
.chip-1 { top: 8%; right: -22px; animation-delay: 280ms; }
.chip-2 { top: 32%; right: -38px; animation-delay: 360ms; }
.chip-3 { top: 56%; right: -18px; animation-delay: 440ms; }
.chip-4 { top: 78%; right: -42px; animation-delay: 520ms; }
.chip-5 { bottom: 4%; left: -22px; animation-delay: 600ms; }

/* ====================================================================
   Promo strip
   ==================================================================== */
.promo {
  text-align: center;
  padding: 64px 0 32px;
  border-top: 1px solid var(--border);
}
.promo-title {
  font-family: var(--font-display);
  font-size: clamp(36px, 4vw, 52px);
  font-weight: 800;
  letter-spacing: -0.025em;
  margin: 0 0 16px;
}
.promo-title::after { content: ' ✕'; color: var(--brand); }
.promo-dek {
  max-width: 760px;
  margin: 0 auto;
  font-size: 14px;
  color: var(--ink-2);
  line-height: 1.7;
}
.promo-dek a {
  color: var(--brand);
  border-bottom: 1px solid var(--brand-soft-2);
}

/* ====================================================================
   Footer
   ==================================================================== */
.footer {
  display: flex;
  align-items: center;
  gap: 16px;
  padding-top: 28px;
  border-top: 1px solid var(--border);
}
.footer a { color: var(--brand); }

/* ====================================================================
   Hidden form — folded into a corner card so the hero stays clean
   ==================================================================== */
.login-card-wrap {
  position: fixed;
  bottom: 32px;
  right: 32px;
  z-index: 20;
  width: 320px;
}
.login-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  padding: 24px 24px 20px;
  box-shadow: 0 12px 32px rgba(22, 93, 255, 0.16);
}
.card-title {
  font-family: var(--font-display);
  font-size: 18px;
  font-weight: 700;
  margin: 0 0 4px;
}
.card-sub { margin: 0 0 16px; font-size: 12px; color: var(--ink-mute); }
.card-sub code {
  font-family: var(--font-mono);
  background: var(--surface-soft);
  padding: 1px 6px;
  border-radius: 3px;
  color: var(--brand);
  font-size: 11px;
}
.card-form { display: flex; flex-direction: column; gap: 12px; }
.field { display: flex; flex-direction: column; gap: 4px; }
.field-label { padding-left: 2px; }
.card-foot {
  margin: 12px 0 0;
  font-size: 11px;
  color: var(--ink-mute);
  text-align: center;
}

/* ====================================================================
   Mobile collapse
   ==================================================================== */
@media (max-width: 960px) {
  .login { padding: 0 20px 32px; }
  .topbar { flex-wrap: wrap; gap: 14px; }
  .nav-links { display: none; }
  .hero { grid-template-columns: 1fr; gap: 40px; padding: 32px 0 48px; }
  .hero-showcase { display: none; }
  .promo-title { font-size: 32px; }
  .login-card-wrap { position: static; width: 100%; margin-top: 24px; }
}
</style>