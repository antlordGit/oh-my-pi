import { ref, watch } from 'vue'
import { defineStore } from 'pinia'

const THEME_KEY = 'omp.theme'

export const useThemeStore = defineStore('theme', () => {
  const isDark = ref(false) // 默认永久亮色

  function bootstrap() {
    // 不再读取存储，强制亮色
    isDark.value = false
    applyTheme()
  }

  function toggle() {
    // 空操作，不再支持切换主题
  }

  function applyTheme() {
    // 永久保存为 light
    try {
      uni.setStorageSync(THEME_KEY, 'light')
    } catch {
      // 静默
    }
  }

  function themeClass() {
    return 'theme-light' // 固定返回亮色
  }

  return { isDark, bootstrap, toggle, themeClass }
})
