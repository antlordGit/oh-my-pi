<script setup lang="ts">
import { onMounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { NConfigProvider, NMessageProvider, NDialogProvider, zhCN, type GlobalThemeOverrides } from 'naive-ui'

const auth = useAuthStore()
onMounted(() => {
  auth.bootstrap()
})

// Editorial Light — high contrast, vivid coral accent.
const themeOverrides: GlobalThemeOverrides = {
  common: {
    fontFamily: '"DM Sans", system-ui, -apple-system, "Helvetica Neue", sans-serif',
    fontFamilyMono: '"JetBrains Mono", ui-monospace, "SF Mono", Menlo, monospace',
    bodyColor: '#faf7f2',
    cardColor: '#ffffff',
    modalColor: '#ffffff',
    popoverColor: '#ffffff',
    borderColor: '#e5e0d8',
    dividerColor: '#f0ebe4',
    textColorBase: '#1a1c1e',
    textColor1: '#1a1c1e',
    textColor2: '#4e5256',
    textColor3: '#8b8f94',
    primaryColor: '#e04e3c',
    primaryColorHover: '#f06b5a',
    primaryColorPressed: '#b83a2c',
    primaryColorSuppl: '#e04e3c',
    successColor: '#2d8a5e',
    warningColor: '#d4851e',
    errorColor: '#d14343',
    borderRadius: '10px',
    borderRadiusSmall: '6px',
    fontWeightStrong: '600',
  },
  Card: {
    paddingMedium: '0',
  },
}
</script>

<template>
  <NConfigProvider :theme-overrides="themeOverrides" :locale="zhCN">
    <NMessageProvider>
      <NDialogProvider>
        <router-view />
      </NDialogProvider>
    </NMessageProvider>
  </NConfigProvider>
</template>
