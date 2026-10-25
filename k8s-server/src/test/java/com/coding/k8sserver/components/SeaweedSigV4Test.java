package com.coding.k8sserver.components;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SigV4 签名护栏：期望值由一份**独立实现**（Python hmac/sha256）算出并固定在这里，
 * 避免"用自己的代码验证自己的代码"。改签名逻辑时这个测试会立刻红。
 */
class SeaweedSigV4Test {

    @Test
    void signsTheSeaweedfsQuotaRequestLikeAwsSigV4() {
        byte[] payload = ("{\"quota_size\":1073741824,\"quota_unit\":\"B\","
                + "\"quota_enabled\":true}").getBytes(StandardCharsets.UTF_8);

        Map<String, String> headers = SeaweedSigV4.sign("PUT", "192.168.31.80:38333", "/t-aaaa-logs",
                "seaweedfs-quota=", payload, "AKIATESTKEY", "secretkey", "20261025T101112Z");

        assertThat(headers.get("x-amz-date")).isEqualTo("20261025T101112Z");
        assertThat(headers.get("x-amz-content-sha256"))
                .isEqualTo("9168102da42866b9dbdff2bf74240e0094c436068eb49d3ac0920a8633a3d0bc");
        assertThat(headers.get("Authorization")).isEqualTo(
                "AWS4-HMAC-SHA256 Credential=AKIATESTKEY/20261025/us-east-1/s3/aws4_request, "
                        + "SignedHeaders=host;x-amz-content-sha256;x-amz-date, "
                        + "Signature=029a4cd811843704d807cf0feacd09d28e433ea6bcfb5e09b83c3d3e3f319836");
    }
}
