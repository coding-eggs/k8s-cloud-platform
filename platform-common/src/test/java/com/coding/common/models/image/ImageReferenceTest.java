package com.coding.common.models.image;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 镜像引用改写规则（镜像来源纳管，2026-10-28）。
 *
 * <p>这些断言就是「Harbor 里的镜像长什么样」的口径，改规则前先看这里。
 */
class ImageReferenceTest {

    private static final String HOST = "harbor.ackcp.cloud";
    private static final String PROJECT = "";   // 默认：一上游一项目

    @Test
    void normalizesDockerHubShorthand() {
        assertThat(ImageReference.parse("redis:7.2").mirror(HOST, PROJECT))
                .isEqualTo("harbor.ackcp.cloud/docker.io/library/redis:7.2");
        assertThat(ImageReference.parse("prometheus/prometheus:v2").mirror(HOST, PROJECT))
                .isEqualTo("harbor.ackcp.cloud/docker.io/prometheus/prometheus:v2");
    }

    @Test
    void keepsUpstreamHostAsTheFirstPathSegment() {
        assertThat(ImageReference.parse("quay.io/opstree/redis:v7.0.15").mirror(HOST, PROJECT))
                .isEqualTo("harbor.ackcp.cloud/quay.io/opstree/redis:v7.0.15");
        assertThat(ImageReference.parse("docker.elastic.co/elasticsearch/elasticsearch:9.5.0")
                .mirror(HOST, PROJECT))
                .isEqualTo("harbor.ackcp.cloud/docker.elastic.co/elasticsearch/elasticsearch:9.5.0");
        // 带端口的主机：端口属于主机名，不能被当成 tag；路径段里的冒号要换掉（Harbor 不允许）
        assertThat(ImageReference.parse("registry.local:5000/team/app:1.0").mirror(HOST, PROJECT))
                .isEqualTo("harbor.ackcp.cloud/registry.local_5000/team/app:1.0");
    }

    @Test
    void keepsDigest() {
        String digest = "@sha256:" + "a".repeat(64);
        assertThat(ImageReference.parse("quay.io/a/b" + digest).mirror(HOST, PROJECT))
                .isEqualTo("harbor.ackcp.cloud/quay.io/a/b" + digest);
    }

    @Test
    void rewriteIsIdempotent() {
        String once = ImageReference.parse("ghcr.io/seaweedfs/seaweedfs:3.80")
                .mirror(HOST, PROJECT);
        assertThat(ImageReference.parse(once).mirror(HOST, PROJECT)).isEqualTo(once);
    }

    @Test
    void untaggedImageKeepsNoTag() {
        assertThat(ImageReference.parse("alpine").mirror(HOST, PROJECT))
                .isEqualTo("harbor.ackcp.cloud/docker.io/library/alpine");
    }

    @Test
    void looksLikeImageFiltersNonImageValues() {
        assertThat(ImageReference.looksLikeImage("quay.io/opstree/redis:v7.0.15")).isTrue();
        assertThat(ImageReference.looksLikeImage("redis:7")).isTrue();
        assertThat(ImageReference.looksLikeImage("prometheus/prometheus")).isTrue();

        assertThat(ImageReference.looksLikeImage(null)).isFalse();
        assertThat(ImageReference.looksLikeImage("")).isFalse();
        assertThat(ImageReference.looksLikeImage("IfNotPresent")).isFalse();
        assertThat(ImageReference.looksLikeImage("Always")).isFalse();
        assertThat(ImageReference.looksLikeImage("https://get.infini.cloud/x")).isFalse();
        assertThat(ImageReference.looksLikeImage("/var/tempo/traces")).isFalse();
        assertThat(ImageReference.looksLikeImage("168h")).isFalse();
    }
}
