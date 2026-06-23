<script setup lang="ts">
// Register-based SVG icon set — 阿里 iconfont 风格，微信手势/尺寸
// 仅供跨端统一视觉，不纠缠组件 glyph 实现
defineProps<{ name: string; size?: string | number }>()

const SZ = '24', SW = '2', LC = 'round', LJ = 'round'
const getSize = (s: string | number | undefined) => (s ? String(s) : '24')

const ICONS: Record<string, { tag: string; attrs: string; content?: string }> = {
  back:       { tag: 'path', attrs: `d="M15 4L7 12L15 20" fill="none" stroke="currentColor" stroke-width="${SW}" stroke-linecap="${LC}" stroke-linejoin="${LJ}"` },
  close:      { tag: 'path', attrs: `d="M6 6L18 18M18 6L6 18" fill="none" stroke="currentColor" stroke-width="${SW}" stroke-linecap="${LC}"` },
  plus:       { tag: 'path', attrs: `d="M12 5V19M5 12H19" fill="none" stroke="currentColor" stroke-width="${SW}" stroke-linecap="${LC}"` },
  send:       { tag: 'g', attrs: '', content: `<path d="M22 2L11 13" fill="none" stroke="currentColor" stroke-width="${SW}" stroke-linecap="${LC}" stroke-linejoin="${LJ}"/><path d="M22 2L15 22L11 13L2 9L22 2Z" fill="currentColor" opacity="0.85"/>` },
  stop:       { tag: 'rect', attrs: `x="4" y="4" width="16" height="16" rx="3" fill="currentColor"` },
  image:      { tag: 'g', attrs: '', content: `<rect x="3" y="3" width="18" height="18" rx="3" fill="none" stroke="currentColor" stroke-width="${SW}"/><circle cx="8.5" cy="8.5" r="1.8" fill="currentColor"/><path d="M3 16L8 11L12 15L16 10L21 17" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="${LC}" stroke-linejoin="${LJ}"/>` },
  newChat:    { tag: 'g', attrs: '', content: `<path d="M21 15C21 18.314 18.314 21 15 21H8L3 23V9C3 5.686 5.686 3 9 3H15C18.314 3 21 5.686 21 9V15Z" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="${LC}" stroke-linejoin="${LJ}"/><path d="M12 8V16M8 12H16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="${LC}"/>` },
  user:       { tag: 'g', attrs: '', content: `<circle cx="12" cy="8" r="4" fill="none" stroke="currentColor" stroke-width="1.8"/><path d="M4 21C4 16.582 7.582 13 12 13C16.418 13 20 16.582 20 21" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="${LC}"/>` },
  robot:      { tag: 'g', attrs: '', content: `<rect x="4" y="5" width="16" height="14" rx="4" fill="none" stroke="currentColor" stroke-width="1.8"/><circle cx="9" cy="11" r="1.5" fill="currentColor"/><circle cx="15" cy="11" r="1.5" fill="currentColor"/><rect x="8" y="15" width="8" height="2" rx="1" fill="currentColor" opacity="0.5"/><path d="M12 2V5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="${LC}"/><circle cx="12" cy="2" r="1.2" fill="currentColor"/>` },
  check:      { tag: 'path', attrs: `d="M5 13L9 17L19 7" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="${LC}" stroke-linejoin="${LJ}"` },
  archive:    { tag: 'g', attrs: '', content: `<rect x="2" y="5" width="20" height="4" rx="1" fill="none" stroke="currentColor" stroke-width="1.8"/><path d="M4 9V20H20V9" fill="none" stroke="currentColor" stroke-width="1.8"/><path d="M10 13H14" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="${LC}"/>` },
  trash:      { tag: 'g', attrs: '', content: `<path d="M4 6H20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="${LC}"/><path d="M8 6V4H16V6" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="${LC}"/><path d="M6 6L7 20H17L18 6" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="${LC}"/><path d="M10 10V16M14 10V16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="${LC}"/>` },
  thinking:   { tag: 'g', attrs: '', content: `<path d="M17 17H19C20.657 17 22 15.657 22 14C22 12.343 20.657 11 19 11C18.732 9.135 17.062 7.756 15.1 8.003C14.068 7.386 12.852 7.022 11.567 7.022C8.044 7.022 5.246 9.731 5.023 13.135C3.349 13.598 2.106 15.138 2.106 16.978C2.106 19.205 3.912 21.011 6.139 21.011H10" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="${LC}" stroke-linejoin="${LJ}"/><circle cx="8" cy="15" r="1" fill="currentColor"/><circle cx="11" cy="15" r="1" fill="currentColor"/><circle cx="14" cy="15" r="1" fill="currentColor"/>` },
}

function renderIcon(name: string): string {
  const def = ICONS[name]
  if (!def) return `<circle cx="12" cy="12" r="10" fill="none" stroke="currentColor" stroke-width="${SW}"/><circle cx="12" cy="12" r="3" fill="currentColor"/>`
  if (def.tag === 'g') return def.content || ''
  return `<${def.tag} ${def.attrs}/>`
}
</script>

<template>
  <view
    class="omp-icon"
    :style="{ width: `${getSize(size)}px`, height: `${getSize(size)}px` }"
    v-html="`<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' width='${getSize(size)}' height='${getSize(size)}'>${renderIcon(name)}</svg>`"
  />
</template>

<style scoped>
.omp-icon { display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; line-height: 1; }
</style>
