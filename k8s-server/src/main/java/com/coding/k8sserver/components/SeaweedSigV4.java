package com.coding.k8sserver.components;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极小的 AWS SigV4 签名器，只服务 SeaweedFS 的 {@code ?seaweedfs-quota} 扩展。
 *
 * <p><b>为什么不用 MinIO SDK</b>：SDK 没有暴露"自定义 query 子资源"的能力，而 SeaweedFS 的每桶配额
 * 就是 {@code PUT /{bucket}?seaweedfs-quota}（JSON body）。签名本身是标准算法，这里只实现 S3 需要的
 * header 签名（不使用 UNIGNORABLE 参数，也不需要 session token）。
 *
 * <p><b>region</b>：SeaweedFS 不校验 region 与桶位置是否一致，固定 {@code us-east-1} 即可；
 * 关键是签名里的 scope 与实际请求一致。
 */
public final class SeaweedSigV4 {

    static final String REGION = "us-east-1";
    static final String SERVICE = "s3";
    static final String CONTENT_TYPE = "application/json";

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /** 复用连接：每次建桶/改配额都会调一次，不值得每次 new 一个客户端 */
    private static final HttpClient CLIENT = HttpClient.newBuilder().build();

    private SeaweedSigV4() {
    }

    /**
     * 发一个签好名的 PUT（query 只有一项、值为空 —— SeaweedFS 的 {@code ?seaweedfs-quota} 就长这样）。
     *
     * @return HTTP 状态码（调用方判断 2xx）
     */
    public static int putJsonQuery(String endpoint, String canonicalPath, String queryName,
                                   byte[] payload, String accessKey, String secretKey) throws Exception {
        URI base = URI.create(endpoint.endsWith("/")
                ? endpoint.substring(0, endpoint.length() - 1) : endpoint);
        String host = base.getPort() > 0 ? base.getHost() + ":" + base.getPort() : base.getHost();
        String amzDate = AMZ_DATE.format(Instant.now());
        Map<String, String> headers = sign("PUT", host, canonicalPath, queryName + "=",
                payload, accessKey, secretKey, amzDate);

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(base.getScheme() + "://" + host + canonicalPath + "?" + queryName))
                .header("x-amz-date", headers.get("x-amz-date"))
                .header("x-amz-content-sha256", headers.get("x-amz-content-sha256"))
                .header("Authorization", headers.get("Authorization"))
                .header("Content-Type", CONTENT_TYPE)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /**
     * 纯计算部分（可单测）：返回 Authorization / x-amz-date / x-amz-content-sha256。
     *
     * @param host           含端口，如 {@code 192.168.31.80:38333}
     * @param canonicalPath  如 {@code /t-aaaa-logs}
     * @param canonicalQuery 已排序、已编码的 query，如 {@code seaweedfs-quota=}
     * @param amzDate        {@code yyyyMMdd'T'HHmmss'Z'}（UTC）
     */
    static Map<String, String> sign(String method, String host, String canonicalPath, String canonicalQuery,
                                    byte[] payload, String accessKey, String secretKey, String amzDate) {
        String payloadHash = hex(sha256(payload));
        String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
        String canonicalHeaders = "host:" + host + "\n"
                + "x-amz-content-sha256:" + payloadHash + "\n"
                + "x-amz-date:" + amzDate + "\n";
        String canonicalRequest = method + "\n" + canonicalPath + "\n" + canonicalQuery + "\n"
                + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;

        String dateStamp = amzDate.substring(0, 8);
        String scope = dateStamp + "/" + REGION + "/" + SERVICE + "/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

        byte[] signingKey = hmac(hmac(hmac(hmac(
                        ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), dateStamp), REGION), SERVICE),
                "aws4_request");
        String signature = hex(hmac(signingKey, stringToSign));

        Map<String, String> out = new LinkedHashMap<>();
        out.put("x-amz-date", amzDate);
        out.put("x-amz-content-sha256", payloadHash);
        out.put("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature);
        return out;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 不可用", e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
