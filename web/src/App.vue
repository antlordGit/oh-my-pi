<script setup lang="ts">
import { onMounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import {
  NConfigProvider, NMessageProvider, NDialogProvider,
  zhCN, type GlobalThemeOverrides,
} from 'naive-ui'

const auth = useAuthStore()
onMounted(() => {
  auth.bootstrap()
})

// "Volcano" — Volcengine Agent Plan inspired.
// Pure white, brand blue #165DFF, heavy Chinese display type.
const themeOverrides: GlobalThemeOverrides = {
  common: {
    fontFamily: '"PingFang SC", "HarmonyOS Sans SC", "Source Han Sans SC", "Noto Sans SC", "Microsoft YaHei", "Inter", system-ui, -apple-system, sans-serif',
    fontFamilyMono: '"JetBrains Mono", "SF Mono", ui-monospace, Menlo, monospace',
    bodyColor: '#FFFFFF',
    cardColor: '#FFFFFF',
    modalColor: '#FFFFFF',
    popoverColor: '#FFFFFF',
    borderColor: '#E5E6EB',
    dividerColor: '#E5E6EB',
    textColorBase: '#1D2129',
    textColor1: '#1D2129',
    textColor2: '#4E5969',
    textColor3: '#86909C',
    primaryColor: '#165DFF',
    primaryColorHover: '#0E49D6',
    primaryColorPressed: '#0A3DB8',
    primaryColorSuppl: '#165DFF',
    infoColor: '#165DFF',
    infoColorHover: '#0E49D6',
    successColor: '#00B42A',
    warningColor: '#FF7D00',
    errorColor: '#F53F3F',
    borderRadius: '12px',
    borderRadiusSmall: '8px',
    fontWeightStrong: '600',
  },
  Card: {
    paddingMedium: '0',
  },
}
</script>

<template>
  <NConfigProvider
    :theme-overrides="themeOverrides"
    :locale="zhCN"
    class="omp-root"
  >
    <NMessageProvider>
      <NDialogProvider>
        <router-view />
      </NDialogProvider>
    </NMessageProvider>
  </NConfigProvider>
</template>

<style scoped>
.omp-root {
  min-height: 100vh;
  position: relative;
  z-index: 1;
}
</style>