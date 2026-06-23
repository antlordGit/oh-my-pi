<script setup lang="ts">
import { useCounterStore } from '../stores/counter'

const counter = useCounterStore()
</script>

<template>
  <section class="home">
    <h2>欢迎使用前端模板</h2>
    <p>这是一个 Vue 3 + Vite + Pinia + Vue Router 的最小骨架。</p>
    <div class="counter">
      <span>计数器：<strong>{{ counter.count }}</strong></span>
      <button @click="counter.increment">+1</button>
      <button @click="counter.reset">重置</button>
    </div>
  </section>
</template>

<style scoped>
.home h2 {
  margin: 0 0 8px;
  font-size: 20px;
}
.home p {
  color: #666;
  margin: 0 0 20px;
}
.counter {
  display: flex;
  align-items: center;
  gap: 12px;
}
.counter button {
  padding: 6px 14px;
  border: 1px solid #d0d0d0;
  border-radius: 6px;
  background: #fff;
  cursor: pointer;
}
.counter button:hover {
  border-color: #165dff;
  color: #165dff;
}
</style>