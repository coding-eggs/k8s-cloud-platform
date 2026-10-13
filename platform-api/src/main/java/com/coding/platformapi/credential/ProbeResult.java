package com.coding.platformapi.credential;

/**
 * 一次测试连接的结果。
 *
 * @param ok           通过与否
 * @param message      给用户看的原因（**凭据类失败一律二义文案**，见 spec §5.4）
 * @param suggestedUrl 非空时表示「对端要求改用这个地址」（3xx 的 Location 剥出来的），
 *                     前端要提供一键填入，别让用户手工抄一个长 URL
 */
public record ProbeResult(boolean ok, String message, String suggestedUrl) {

    public static ProbeResult pass() {
        return new ProbeResult(true, "连接成功", null);
    }

    public static ProbeResult fail(String message) {
        return new ProbeResult(false, message, null);
    }

    public static ProbeResult failWithUrl(String message, String suggestedUrl) {
        return new ProbeResult(false, message, suggestedUrl);
    }
}
