import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'
import path from 'node:path'

// uni-app 多端构建配置
//
// - 端选择由 cli 参数控制：`uni -p mp-weixin` / `uni -p app` 等
// - 路径别名 @ 指向 src/，与 H5 工程保持一致
// - sass 通过 additionalData 自动注入设计令牌，所有 <style lang="scss"> 内可直接使用变量
export default defineConfig({
  plugins: [uni()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  css: {
    preprocessorOptions: {
      scss: {
        // 注意：使用 @import 而非 @use，因 additionalData 注入位置在文件末尾，
        // 而 @use 必须出现在最前面。@import 兼容性更好。
        // 同时注入 uview-plus 的 theme，让所有内置组件能用到其 SCSS 变量。
        additionalData: `@import "uview-plus/theme.scss"; @import "@/uni.scss";`,
      },
    },
  },
})
