package com.yourorg.omp.ide;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 工作区 URL 签名器（HMAC-SHA256）。
 *
 * <p>对 {@code ?folder=<明文>} 附加 {@code &sig=<hash>} 参数，
 * code-server 端持同一 key 验签，签名不匹配或缺失的请求返回 403。
 *
 * <p>签名输入：{@code folder=path}（不包含 &sig=）。每次签得的
 * {@code sig} 对同一 folder 相同（确定性），便于 URL 可分享。
 *
 * <p>key 为空时 {@link #enabled()} 返回 false，不附加签名参数。
 */
public final class WorkspaceSigner {

    private static final String ALG = "HmacSHA256";

    private final SecretKeySpec key;

    public WorkspaceSigner(String hexKey) {
        if (hexKey == null || hexKey.isBlank()) {
            this.key = null;
            return;
        }
        if (hexKey.length() != 64) {
            throw new IllegalArgumentException("signing-key 必须是 64 个 hex 字符 (32 字节)");
        }
        byte[] raw = HexFormat.of().parseHex(hexKey);
        this.key = new SecretKeySpec(raw, ALG);
    }

    public boolean enabled() {
        return key != null;
    }

    /**
     * 返回 {@code &sig=<base64url(hash)>} 形式（不含 folder= 部分），
     * 方便拼入已有 URL 末尾。
     */
    public String signParam(String folderPath) {
        if (!enabled()) return "";
        try {
            String input = "folder=" + folderPath;
            Mac mac = Mac.getInstance(ALG);
            mac.init(key);
            byte[] hash = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            return "&sig=" + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("签名失败: " + e.getMessage(), e);
        }
    }
}
