<script setup lang="ts">
import { onMounted, computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import {
  NConfigProvider, NMessageProvider, NDialogProvider,
  zhCN, darkTheme, lightTheme, type GlobalThemeOverrides,
} from 'naive-ui'
import { isDark } from '@/stores/theme'

const auth = useAuthStore()

onMounted(() => {
  auth.bootstrap()
})

const lightOverrides: GlobalThemeOverrides = {
  common: {
    fontFamily: '"PingFang SC", "HarmonyOS Sans SC", "Source Han Sans SC", "Noto Sans SC", "Microsoft YaHei", "Inter", system-ui, -apple-system, sans-serif',
    fontFamilyMono: '"IBM Plex Mono", "JetBrains Mono", "SF Mono", ui-monospace, Menlo, monospace',
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
  Card: { paddingMedium: '0' },
}

const darkOverrides: GlobalThemeOverrides = {
  common: {
    fontFamily: '"PingFang SC", "HarmonyOS Sans SC", "Source Han Sans SC", "Noto Sans SC", "Microsoft YaHei", "Inter", system-ui, -apple-system, sans-serif',
    fontFamilyMono: '"IBM Plex Mono", "JetBrains Mono", "SF Mono", ui-monospace, Menlo, monospace',
    bodyColor: '#0d0f12',
    cardColor: '#12151a',
    modalColor: '#12151a',
    popoverColor: '#12151a',
    borderColor: '#1a1d24',
    dividerColor: '#1a1d24',
    textColorBase: '#e8eaed',
    textColor1: '#e8eaed',
    textColor2: '#8b919d',
    textColor3: '#5a5f6b',
    primaryColor: '#00e5ff',
    primaryColorHover: '#33ebff',
    primaryColorPressed: '#00b8d4',
    primaryColorSuppl: '#00e5ff',
    infoColor: '#00e5ff',
    infoColorHover: '#33ebff',
    successColor: '#00e676',
    warningColor: '#ffb020',
    errorColor: '#f85252',
    borderRadius: '6px',
    borderRadiusSmall: '4px',
    fontWeightStrong: '600',
  },
  Card: { paddingMedium: '0', borderColor: '#1a1d24' },
  DataTable: { borderColor: '#1a1d24' },
  Input: {
    borderColor: '#1a1d24',
    borderColorHover: '#252830',
    borderColorFocus: '#00e5ff',
  },
  Button: { borderColor: '#1a1d24' },
  Tabs: { tabBorderColor: '#1a1d24' },
  Select: {
    borderColor: '#1a1d24',
    optionColorActive: 'rgba(0, 229, 255, 0.08)',
    optionColorActiveHover: 'rgba(0, 229, 255, 0.12)',
  },
  Dialog: { borderColor: '#1a1d24' },
  Popover: { borderColor: '#1a1d24' },
}

const currentTheme = computed(() => isDark.value ? darkTheme : lightTheme)
const currentOverrides = computed(() => isDark.value ? darkOverrides : lightOverrides)
</script>

<template>
  <NConfigProvider
    :theme="currentTheme"
    :theme-overrides="currentOverrides"
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

<style>
:global(body) {
  margin: 0;
  padding: 0;
  min-height: 100vh;
  transition: background 0.3s ease, color 0.3s ease;
}

:global(#app) {
  min-height: 100vh;
}

.omp-root {
  min-height: 100vh;
  position: relative;
}
</style>
