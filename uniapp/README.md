# OMP 工作站（uni-app 跨端版）

> 将 H5 版 OMP 编码工作站改造成 uni-app（Vue 3 + TS + uView Plus）工程，
> 可编译到微信/支付宝/抖音/百度小程序 以及 iOS/Android App，
> 后端协议、API 路径、JWT 鉴权完全不变。

## 快速开始

```bash
# 安装依赖
cd uniapp
npm install       # 或 bun install

# 编译到微信小程序（主要开发目标）
npm run dev:mp-weixin

# 编译到 H5（浏览器调试）
npm run dev:h5

# 编译到 App（iOS/Android）
npm run dev:app

# 其他小程序端
npm run dev:mp-alipay
npm run dev:mp-toutiao
npm run dev:mp-baidu
```

## 微信开发者工具导入

1. 启动 `npm run dev:mp-weixin`，产物在 `dist/dev/mp-weixin/`
2. 打开**微信开发者工具**，导入 `dist/dev/mp-weixin/` 目录
3. 在「详情 → 本地设置」勾选「不校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书」
4. 修改 `src/api/http.ts` 中的 `BASE_URL` 和 `src/pages/chat/chat.vue` 中的 WebSocket IP 为你的后端地址

## 后端要求

- 后端代码无需修改，与 H5 版的 API 完全兼容
- 开发期建议先用 IP 直连，后端监听 `0.0.0.0:8080`
- 确保后端在局域网内可访问（在小程序真机调试时需要）

## 目录结构

```
uniapp/
├── package.json           # 依赖清单
├── tsconfig.json
├── vite.config.ts         # Vite + @dcloudio/vite-plugin-uni
├── manifest.json          # 多端配置（微信appid/超时/分包等）
├── pages.json             # 页面路由 + easycom
├── uni.scss               # 设计令牌（暗/亮双色 CSS 变量）
├── src/
│   ├── main.ts            # createSSRApp + Pinia + uView
│   ├── App.vue            # onLaunch 恢复登录态 + 主题
│   ├── pages/
│   │   ├── login/         # 登录页
│   │   ├── sessions/      # 会话列表（列表+新建+归档+批量删除）
│   │   └── chat/          # 聊天对话（WebSocket 流式 + Markdown + 图片）
│   ├── components/
│   │   ├── MessageBubble  # 消息气泡（markdown-it + mp-html 渲染）
│   │   ├── ToolCard       # 工具调用卡片（展开/收起）
│   │   └── UiRequestDialog # UI 询问弹窗（select/confirm/input/editor）
│   ├── api/
│   │   ├── http.ts        # uni.request 适配器（签名与 axios 兼容）
│   │   ├── session.ts     # 会话 API
│   │   ├── repo.ts        # 仓库 API（仅 listRepos）
│   │   └── system.ts      # 系统 API（仅类型）
│   ├── stores/
│   │   ├── auth.ts        # Pinia 认证 store
│   │   └── theme.ts       # Pinia 主题 store
│   ├── utils/
│   │   ├── websocket.ts   # uni.connectSocket 封装（自动重连）
│   │   ├── image.ts       # 选图 + 转 base64
│   │   └── toast.ts       # 替代 naive-ui useMessage/useDialog
│   └── styles/
│       ├── _tokens.scss   # 令牌入口（forward uni.scss）
│       ├── _mixins.scss   # SCSS mixins
│       ├── _animations.scss # 动画关键帧
│       └── _uview-overrides.scss # uView UI 主题覆盖
└── index.html
```

## 功能范围

| 功能 | 状态 |
|------|------|
| 登录 | ✅ |
| 会话列表（查看/新建/归档/恢复/批量删除） | ✅ |
| 仓库下拉选择（只读） | ✅ |
| WebSocket 实时流式对话 | ✅ |
| 工具调用卡片（ToolCard） | ✅ |
| UI 询问弹窗（select/confirm/input/editor） | ✅ |
| 图片选择上传（≤5张 ≤5MB） | ✅ |
| Markdown 渲染（mp-html） | ✅ |
| 长消息折叠 + 复制全文 | ✅ |
| 思考块折叠 | ✅ |
| 暗/亮双色主题切换 | ✅ |
| /tree 分支命令 | ✅ |
| 中断 / 新对话 | ✅ |
| 拉下刷新会话列表 | ✅ |
| 仓库管理（创建/导入/导出/删除） | ❌ |
| 后台管理（会话审计/维护模式） | ❌ |
| 系统设置（用户/角色/菜单/租户/模型） | ❌ |
| FileEditor 文件编辑器 | ❌ |
| WorkspaceTree 工程树 | ❌ |
| 打开 IDE（code-server 跳转） | ❌ |

## 主题切换

- 默认暗色主题
- 切换后会持久化到 `uni.setStorageSync('omp.theme')`
- 每个页面根 `<view :class="['page', theme.themeClass()]">` 驱动 CSS 变量
- 暗色令牌在 `.theme-dark { ... }` 内，亮色在 `.theme-light { ... }` 内（定义于 `uni.scss`）

## 后端 IP 配置

三个文件涉及后端地址，请按实际 IP 替换：

1. `src/api/http.ts` — `BASE_URL` (HTTP)
2. `src/utils/websocket.ts` — `buildFullUrl()` 中的 `base` (WS)
3. `src/pages/chat/chat.vue` — `connectWs()` 中的 `url` (WS)

## 验证检查清单

- [ ] `npm run dev:mp-weixin` 编译成功
- [ ] 微信开发者工具能正确渲染三个页面
- [ ] 登录 → token 写入 storage → 跳转会话列表
- [ ] 会话列表：拉取数据 + 新建会话 + 跳转聊天
- [ ] 聊天页：WebSocket 连接成功 + 发送消息 + 流式打字机
- [ ] 工具卡片展开/收起
- [ ] UI 询问弹窗（select/confirm/input/editor）
- [ ] 图片选图 → 预览 → base64 发送
- [ ] /tree 命令树形分支
- [ ] 归档 + 恢复 + 批量删除
- [ ] 暗/亮主题切换刷新后保持

## License

与 oh-my-pi-main 项目一致
