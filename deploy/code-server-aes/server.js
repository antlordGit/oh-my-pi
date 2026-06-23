/**
 * code-server 签名验证代理
 *
 * 位于 code-server 进程前，对外监听 9000 端口。
 * 对每个到 /?folder=... 的请求：
 *   1) 如果 folder 参数存在，验证对应 &sig= HMAC
 *   2) 有效 → 透传给上游 code-server (127.0.0.1:8080)
 *   3) 无效/缺失 → 403
 *
 * 支持 WebSocket upgrade（code-server workbench 必需）。
 */

const http = require("http")
const httpProxy = require("http-proxy")
const crypto = require("crypto")

const PORT = process.env.IDE_PROXY_PORT || 9000
const UPSTREAM = process.env.CODE_SERVER_UPSTREAM || "http://127.0.0.1:8080"
const SIGNING_KEY_HEX = process.env.SIGNING_KEY_HEX

if (!SIGNING_KEY_HEX || SIGNING_KEY_HEX.length !== 64) {
  console.error("[signature-proxy] SIGNING_KEY_HEX env 缺失或长度不为 64，proxy 启动失败")
  process.exit(1)
}
const signingKey = Buffer.from(SIGNING_KEY_HEX, "hex")

function computeSig(folder) {
  const hmac = crypto.createHmac("sha256", signingKey)
  hmac.update("folder=" + folder)
  return hmac.digest("base64url")
}

const proxy = httpProxy.createProxyServer({ target: UPSTREAM, ws: true })

// 签名验证函数
function verifyRequest(req) {
  const url = new URL(req.url, "http://localhost")
  const folder = url.searchParams.get("folder")
  const sig = url.searchParams.get("sig")

  if (!folder) return true

  if (!sig) {
    console.log("[signature-proxy] 403: " + req.url + " (缺少 sig)")
    return false
  }
  const expected = computeSig(folder)
  try {
    if (!crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(expected))) {
      console.log("[signature-proxy] 403: " + req.url + " (签名不匹配)")
      return false
    }
  } catch (e) {
    console.log("[signature-proxy] 403: " + req.url + " (timingSafeEqual err)")
    return false
  }
  return true
}

// HTTP 请求
const server = http.createServer((req, res) => {
  if (!verifyRequest(req)) {
    res.writeHead(403).end("无效的工作区标识")
    return
  }
  proxy.web(req, res)
})

// WebSocket upgrade — code-server workbench 必需
server.on("upgrade", (req, socket, head) => {
  if (!verifyRequest(req)) {
    socket.write("HTTP/1.1 403 Forbidden\r\n\r\n")
    socket.destroy()
    return
  }
  proxy.ws(req, socket, head)
})

server.listen(PORT, () => {
  console.log("[signature-proxy] listening on :" + PORT + " → " + UPSTREAM + " (WS enabled)")
})
