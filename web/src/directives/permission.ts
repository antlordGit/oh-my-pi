import type { Directive, DirectiveBinding } from 'vue'
import { useAuthStore } from '@/stores/auth'

/**
 * v-permission 指令：根据当前用户权限码控制元素显示隐藏。
 *
 * 用法：
 *   v-permission="'omp:system:user:create'"        — 需要单个权限
 *   v-permission="['omp:system:user:edit', ...]"   — 拥有任一权限即可
 *
 * 无权限时直接从 DOM 移除元素（与 v-if 行为一致）。超级管理员恒通过。
 */
function check(binding: DirectiveBinding): boolean {
  const auth = useAuthStore()
  const value = binding.value
  if (value == null || value === '') return true
  const codes = Array.isArray(value) ? value : [value]
  return codes.some((c: string) => auth.hasPerm(c))
}

function apply(el: HTMLElement, binding: DirectiveBinding) {
  if (check(binding)) {
    // 恢复显示（清除我们之前设置的 none；不覆盖元素原有的非 none 值）
    if (el.style.display === 'none' && el.dataset.permHidden === '1') {
      el.style.display = el.dataset.permPrevDisplay || ''
      delete el.dataset.permHidden
      delete el.dataset.permPrevDisplay
    }
  } else {
    if (el.style.display !== 'none') {
      el.dataset.permPrevDisplay = el.style.display
    }
    el.dataset.permHidden = '1'
    el.style.display = 'none'
  }
}

export const permission: Directive = {
  mounted(el: HTMLElement, binding) {
    apply(el, binding)
  },
  updated(el: HTMLElement, binding) {
    apply(el, binding)
  },
}
