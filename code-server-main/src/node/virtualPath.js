"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.decryptVirtualPath = exports.buildKeyFromHex = exports.VIRTUAL_PATH_PREFIX = void 0;
const crypto_1 = require("crypto");
const ALG = "aes-256-gcm";
exports.VIRTUAL_PATH_PREFIX = "/virt/";
const IV_LEN = 12;
const TAG_LEN = 16;
/**
 * 从 64 个 hex 字符构造 32 字节 AES-256 key。
 * 空 / undefined → null（特性关闭，原样透传路径）。
 */
function buildKeyFromHex(hex) {
    if (!hex || hex.length === 0)
        return null;
    if (!/^[0-9a-fA-F]{64}$/.test(hex)) {
        throw new Error("--virtual-path-key 必须是 64 个 hex 字符 (32 字节)");
    }
    return Buffer.from(hex, "hex");
}
exports.buildKeyFromHex = buildKeyFromHex;
/**
 * 解密以 {@link VIRTUAL_PATH_PREFIX} 开头的虚拟路径。
 * 失败（GCM 认证失败 / 长度不足）抛 Error，调用方应 403。
 * 不以该前缀开头或 key 为 null → 原样返回（兼容未启用 / 旧 URL）。
 */
function decryptVirtualPath(value, key) {
    if (!value.startsWith(exports.VIRTUAL_PATH_PREFIX) || !key)
        return value;
    const raw = Buffer.from(value.slice(exports.VIRTUAL_PATH_PREFIX.length), "base64url");
    if (raw.length < IV_LEN + TAG_LEN) {
        throw new Error("无效的工作区标识");
    }
    const iv = raw.subarray(0, IV_LEN);
    const tag = raw.subarray(raw.length - TAG_LEN);
    const ct = raw.subarray(IV_LEN, raw.length - TAG_LEN);
    const decipher = (0, crypto_1.createDecipheriv)(ALG, key, iv);
    decipher.setAuthTag(tag);
    return Buffer.concat([decipher.update(ct), decipher.final()]).toString("utf8");
}
exports.decryptVirtualPath = decryptVirtualPath;
