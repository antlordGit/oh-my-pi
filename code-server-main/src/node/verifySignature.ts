import { createHmac } from "crypto"

/**
 * 从 64 个 hex 字符构造 32 字节 HMAC-SHA256 key。
 * 空 / undefined → null（特性关闭，不验签）。
 */
export function buildKeyFromHex(hex: string | undefined): Buffer | null {
  if (!hex || hex.length === 0) return null
  if (!/^[0-9a-fA-F]{64}$/.test(hex)) {
    throw new Error("--signing-key 必须是 64 个 hex 字符 (32 字节)")
  }
  return Buffer.from(hex, "hex")
}

/**
 * 对 folder 参数计算 HMAC-SHA256 并返回 base64url 签名。
 * 与 Java 端 WorkspaceSigner.signParam() 算法对称：sign = HMAC("folder=" + path)
 */
export function computeSig(folder: string, key: Buffer): string {
  const hmac = createHmac("sha256", key)
  hmac.update("folder=" + folder)
  return hmac.digest("base64url")
}
