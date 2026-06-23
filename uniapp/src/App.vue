<script setup lang="ts">
import { onLaunch, onShow, onHide } from '@dcloudio/uni-app'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'

onLaunch(() => {
  const theme = useThemeStore()
  theme.bootstrap()
  const auth = useAuthStore()
  auth.bootstrap()
})

onShow(() => {})
onHide(() => {})
</script>

<style lang="scss">
// ====================================================================
// 全局 CSS 变量 — 微信亮色主题（无 scoped，所有页面/组件可见）
// ====================================================================

$font-display: -apple-system, BlinkMacSystemFont, "PingFang SC", "Helvetica Neue",
  "Microsoft YaHei", system-ui, sans-serif;
$font-ui: -apple-system, BlinkMacSystemFont, "PingFang SC", "Helvetica Neue",
  "Microsoft YaHei", system-ui, sans-serif;
$font-mono: "SF Mono", "JetBrains Mono", ui-monospace, Menlo, monospace;
$fs-body: 17px;
$fs-sub: 15px;
$fs-foot: 13px;

page {
  --canvas: #ededed;
  --chat-bg: #ededed;
  --surface: #ffffff;
  --surface-soft: #f7f7f7;
  --surface-hover: #f0f0f0;

  --border: rgba(0, 0, 0, 0.06);
  --border-strong: rgba(0, 0, 0, 0.12);
  --border-soft: rgba(0, 0, 0, 0.03);
  --separator: #e5e5e5;

  --brand: #07c160;
  --brand-hover: #06ad55;
  --brand-pressed: #05994a;
  --brand-soft: rgba(7, 193, 96, 0.1);
  --brand-soft-2: rgba(7, 193, 96, 0.18);
  --brand-deep: #05994a;

  --bubble-self: #95ec69;
  --bubble-self-text: #191919;
  --bubble-other: #ffffff;
  --bubble-other-text: #191919;

  --ink: #191919;
  --ink-2: #5a5a5a;
  --ink-mute: #888888;
  --ink-faint: #b5b5b5;
  --ink-invert: #ffffff;

  --good: #07c160;
  --good-soft: rgba(7, 193, 96, 0.08);
  --warn: #fa9d3b;
  --warn-soft: rgba(250, 157, 59, 0.1);
  --danger: #fa5151;
  --danger-soft: rgba(250, 81, 81, 0.08);
  --link: #576b95;

  --shadow-card: 0 0.5px 0 rgba(0, 0, 0, 0.05);
  --shadow-hover: 0 4px 16px rgba(0, 0, 0, 0.08);
  --shadow-brand: 0 4px 14px rgba(7, 193, 96, 0.24);
  --shadow-focus: 0 0 0 3px rgba(7, 193, 96, 0.2);

  --fg: var(--ink);
  --fg-mute: var(--ink-2);
  --fg-dim: var(--ink-mute);
  --fg-faint: var(--ink-faint);
  --bg: var(--canvas);
  --bg-soft: var(--surface-soft);

  --font-display: #{$font-display};
  --font-ui: #{$font-ui};
  --font-mono: #{$font-mono};

  --radius: 12px;
  --radius-sm: 8px;
  --radius-xs: 6px;
  --radius-pill: 9999px;
  --radius-card: 12px;
  --radius-bubble: 10px;

  --dur-fast: 180ms;
  --dur-base: 260ms;
  --dur-slow: 420ms;
  --ease-out: cubic-bezier(0.4, 0, 0.2, 1);
  --ease-spring: cubic-bezier(0.32, 0.72, 0, 1);

  margin: 0;
  padding: 0;
  min-height: 100vh;
  background: #ededed !important;
  color: #191919 !important;
  font-family: $font-ui;
  font-size: #{$fs-body};
  line-height: 1.4;
  -webkit-font-smoothing: antialiased;
  overflow-x: hidden;
  box-sizing: border-box;
}

// 强制覆盖 uni-app H5 运行时的深色默认样式
uni-page,
uni-page-wrapper,
uni-page-body {
  background: var(--canvas) !important;
  color: var(--ink) !important;
}

view, text, scroll-view, swiper, movable-view, cover-view {
  box-sizing: border-box;
}

// ====================================================================
// 工具类（全局）
// ====================================================================

.display {
  font-family: var(--font-display);
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--ink);
}

.mono {
  font-family: var(--font-mono);
}

// 微信通用 cell
.cell {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  background: var(--surface);
  position: relative;
}
.cell + .cell::before {
  content: "";
  position: absolute;
  top: 0;
  left: 16px;
  right: 0;
  height: 1px;
  background: var(--separator);
  transform: scaleY(0.5);
}

.group {
  background: var(--surface);
  margin: 12px 16px;
  border-radius: 12px;
  overflow: hidden;
}

.btn-wx {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 44px;
  padding: 0 24px;
  border-radius: 8px;
  background: var(--brand);
  color: var(--ink-invert);
  font-size: 17px;
  font-weight: 500;
  border: none;
}
.btn-wx:active { background: var(--brand-pressed); }
.btn-wx:disabled { opacity: 0.45; }

// 动画
@keyframes fade-up {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}
@keyframes gentle-pulse {
  0%, 100% { opacity: 0.55; }
  50% { opacity: 1; }
}
@keyframes blink {
  0%, 100% { opacity: 0.2; }
  50% { opacity: 1; }
}
@keyframes typing-bounce {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.4; }
  30% { transform: translateY(-4px); opacity: 1; }
}

.fade-up { animation: fade-up var(--dur-base) var(--ease-out) both; }
.live-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--brand);
  animation: gentle-pulse 2s ease-in-out infinite;
}
.cursor-blink {
  display: inline-block;
  width: 2px;
  height: 14px;
  background: var(--brand);
  animation: blink 1s steps(2, start) infinite;
  vertical-align: text-bottom;
  margin-left: 2px;
}
</style>