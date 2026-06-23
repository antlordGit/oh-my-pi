import { defineStore } from 'pinia'
import { ref } from 'vue'

/** 示例：counter store，演示 Pinia 基础用法 */
export const useCounterStore = defineStore('counter', () => {
  const count = ref(0)
  function increment() {
    count.value++
  }
  function reset() {
    count.value = 0
  }
  return { count, increment, reset }
})