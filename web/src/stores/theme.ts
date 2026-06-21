import { ref, watch, onMounted } from 'vue'

export const isDark = ref(true)

onMounted(() => {
  const saved = localStorage.getItem('omp-theme')
  if (saved !== null) {
    isDark.value = saved === 'dark'
  }
  applyTheme()
})

watch(isDark, () => {
  localStorage.setItem('omp-theme', isDark.value ? 'dark' : 'light')
  applyTheme()
})

function applyTheme() {
  document.documentElement.setAttribute('data-theme', isDark.value ? 'dark' : 'light')
  document.body.classList.toggle('light', !isDark.value)
}

export function toggleTheme() {
  isDark.value = !isDark.value
}
