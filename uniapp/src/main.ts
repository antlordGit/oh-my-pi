import { createSSRApp } from 'vue'
import { createPinia } from 'pinia'
import uviewPlus from 'uview-plus'
import App from './App.vue'

// uni-app 入口：必须用 createSSRApp 才能跨端
// uView Plus 通过 app.use 注册全局组件与 $u 实例方法
export function createApp() {
  const app = createSSRApp(App)
  app.use(createPinia())
  app.use(uviewPlus)
  return { app }
}
