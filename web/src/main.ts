import { createApp } from 'vue'
import { createPinia } from 'pinia'
import naive from 'naive-ui'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import { router } from './router'
import { permission } from './directives/permission'
import './styles/design-system.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(naive)
app.use(ElementPlus)
app.directive('permission', permission)
app.mount('#app')