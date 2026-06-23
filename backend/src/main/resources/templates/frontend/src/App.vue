<script setup lang="ts">
import { RouterView } from 'vue-router'
</script>

<template>
  <div class="app">
    <header class="app-header">
      <h1>Frontend Template</h1>
    </header>
    <main class="app-main">
      <RouterView />
    </main>
  </div>
</template>

<style>
:root {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  color: #1a1a1a;
  background: #fafafa;
}
.app {
  max-width: 960px;
  margin: 0 auto;
  padding: 24px;
}
.app-header h1 {
  margin: 0 0 24px;
  font-size: 24px;
}
.app-main {
  background: #fff;
  border: 1px solid #e5e5e5;
  border-radius: 8px;
  padding: 24px;
}
</style>