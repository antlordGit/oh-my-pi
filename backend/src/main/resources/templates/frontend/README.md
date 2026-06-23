# Frontend Template

Vue 3 + Vite + Pinia + Vue Router + Axios 最小可运行骨架。

## 启动

```bash
npm install
npm run dev
```

默认监听 `http://localhost:5173`。

## 目录

- `src/main.ts` —— 入口
- `src/App.vue` —— 根组件
- `src/router/index.ts` —— 路由
- `src/stores/counter.ts` —— Pinia 示例 store
- `src/views/Home.vue` —— 主页

## 自定义

1. 修改 `package.json` 的 `name` 为你的仓库标识
2. 在 `src/views/` 下新增页面，在 `src/router/index.ts` 注册路由
3. 在 `src/stores/` 下新增 Pinia store