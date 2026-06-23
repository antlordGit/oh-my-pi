# code-server-main 启动方式

code-server-main 是基于 [code-server](https://github.com/coder/code-server) 源码修改的定制版本，位于 `code-server-main/` 目录下。

## 启动命令

```bash
cd /Users/chenzhiwei/work/github/oh-my-pi-main/code-server-main
nohup node out/node/entry.js \
  --bind-addr 0.0.0.0:9090 \
  --auth none \
  --signing-key 6d027e57b534087e44c59886d602bd097e5c0f175b6db8cf9c7e81de40abaf67 \
  /Users/chenzhiwei/work/github/oh-my-pi-main &
```

## 参数说明

| 参数 | 值 | 说明 |
|---|---|---|
| `--bind-addr` | `0.0.0.0:9090` | 监听地址和端口，当前固定 9090 |
| `--auth` | `none` | 禁用密码认证 |
| `--signing-key` | 64 位 hex | HMAC-SHA256 签名密钥，用于工作区 URL 验签 |
| 末尾路径 | 项目根目录 | code-server 打开的工作区目录 |

## 签名密钥生成

密钥是 64 位 hex 字符串（32 字节），用于 HMAC-SHA256 签名。

```bash
# 方式一：OpenSSL
openssl rand -hex 32

# 方式二：Node.js
node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"

# 方式三：Bun
bun -e "console.log(Bun.randomBytes(32).toString('hex'))"
```

## 签名密钥一致性

更换密钥后，**两端必须同时更新**：

1. **code-server-main** 启动命令中 `--signing-key` 的值
2. **后端** `application.yml` 中 `app.omp.ide.signing-key` 的值

> 如果两边 key 不一致，签名验证永远失败，返回 403"无效的工作区标识"。

## 访问方式

通过后端生成的 IDE URL 访问：

```
http://localhost:9090/?folder=/tmp/omp/workspaces/<user>/<repoId>&sig=<后端计算的HMAC签名>
```

直接手工访问时，`sig` 值需要通过后端 `POST /api/sessions/{id}/ide/open` 接口获取，或自行用同一 key 计算 `HMAC-SHA256("folder=" + folderPath)` 的 base64url 编码。

## 相关补丁

`code-server-main/patches/` 下包含的定制补丁：

- `signature-verification.diff` — 禁用 VS Code 扩展签名验证
- `proxy-uri.diff` — 支持 `VSCODE_PROXY_URI` 环境变量，终端/扩展可通过代理访问 localhost 端口
- `app-name.diff` — 支持 `--app-name` 参数自定义浏览器标签标题
- `base-path.diff` — 支持反向代理子路径部署
- 其余补丁见 `patches/` 目录
