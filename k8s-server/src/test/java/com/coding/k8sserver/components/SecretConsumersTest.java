package com.coding.k8sserver.components;

import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvFromSourceBuilder;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.ProjectedVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.SecretEnvSourceBuilder;
import io.fabric8.kubernetes.api.model.SecretProjectionBuilder;
import io.fabric8.kubernetes.api.model.SecretVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeProjectionBuilder;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "这个 Pod 模板是不是会读那个 Secret" —— 决定 Secret 内容变了之后要滚动谁。
 *
 * <p>平台写的配置 Secret 不进 helm values，chart 自带的 checksum 注解看不到它的内容，
 * 所以只能由平台自己找出消费者来重启（见 thanos README §6）。
 */
class SecretConsumersTest {

    private static final String SECRET = "platform-thanos-objstore";

    @Test
    void volumeMountCounts() {
        PodSpec spec = new PodSpecBuilder()
                .withContainers(new ContainerBuilder().withName("compactor").build())
                .withVolumes(new VolumeBuilder()
                        .withName("objstore")
                        .withSecret(new SecretVolumeSourceBuilder().withSecretName(SECRET).build())
                        .build())
                .build();

        assertThat(SecretConsumers.podSpecUsesSecret(spec, SECRET)).isTrue();
    }

    @Test
    void projectedVolumeCounts() {
        PodSpec spec = new PodSpecBuilder()
                .withContainers(new ContainerBuilder().withName("c").build())
                .withVolumes(new VolumeBuilder()
                        .withName("all")
                        .withProjected(new ProjectedVolumeSourceBuilder()
                                .withSources(new VolumeProjectionBuilder()
                                        .withSecret(new SecretProjectionBuilder().withName(SECRET).build())
                                        .build())
                                .build())
                        .build())
                .build();

        assertThat(SecretConsumers.podSpecUsesSecret(spec, SECRET)).isTrue();
    }

    @Test
    void envFromCounts() {
        PodSpec spec = new PodSpecBuilder()
                .withContainers(new ContainerBuilder().withName("c")
                        .withEnvFrom(new EnvFromSourceBuilder()
                                .withSecretRef(new SecretEnvSourceBuilder().withName(SECRET).build())
                                .build())
                        .build())
                .build();

        assertThat(SecretConsumers.podSpecUsesSecret(spec, SECRET)).isTrue();
    }

    @Test
    void envValueFromCountsIncludingInitContainers() {
        PodSpec spec = new PodSpecBuilder()
                .withInitContainers(new ContainerBuilder().withName("init")
                        .withEnv(new EnvVarBuilder().withName("SECRET_KEY")
                                .withNewValueFrom()
                                .withNewSecretKeyRef().withName(SECRET).withKey("secret-key").endSecretKeyRef()
                                .endValueFrom()
                                .build())
                        .build())
                .build();

        assertThat(SecretConsumers.podSpecUsesSecret(spec, SECRET)).isTrue();
    }

    @Test
    void otherSecretsDoNotCount() {
        PodSpec spec = new PodSpecBuilder()
                .withContainers(new ContainerBuilder().withName("c").build())
                .withVolumes(new VolumeBuilder().withName("v")
                        .withSecret(new SecretVolumeSourceBuilder().withSecretName("other-secret").build())
                        .build())
                .build();

        assertThat(SecretConsumers.podSpecUsesSecret(spec, SECRET)).isFalse();
    }

    @Test
    void nullAndEmptyInputsDoNotCount() {
        assertThat(SecretConsumers.podSpecUsesSecret(null, SECRET)).isFalse();
        assertThat(SecretConsumers.podSpecUsesSecret(new PodSpec(), "")).isFalse();
        assertThat(SecretConsumers.podSpecUsesSecret(new PodSpec(), null)).isFalse();
    }

    @Test
    void hashIsStableAndContentSensitive() {
        assertThat(SecretConsumers.hashOf("endpoint: a\n")).hasSize(64)
                .isEqualTo(SecretConsumers.hashOf("endpoint: a\n"));
        assertThat(SecretConsumers.hashOf("endpoint: a\n"))
                .isNotEqualTo(SecretConsumers.hashOf("endpoint: b\n"));
    }

    @Test
    void keyValueHashIgnoresMapOrder() {
        String a = SecretConsumers.hashOf(Map.of("access-key", "AK", "secret-key", "SK"));
        String b = SecretConsumers.hashOf(new java.util.LinkedHashMap<>(
                Map.of("secret-key", "SK", "access-key", "AK")));

        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(SecretConsumers.hashOf(Map.of("access-key", "AK", "secret-key", "SK2")));
    }

    @Test
    void alignmentIsNeededUntilTheTemplateRecordsThatHash() {
        String expected = SECRET + ":abc";

        assertThat(SecretConsumers.needsAlign(null, expected)).isTrue();
        assertThat(SecretConsumers.needsAlign(Map.of(), expected)).isTrue();
        assertThat(SecretConsumers.needsAlign(
                Map.of(SecretConsumers.CONFIG_HASH_ANNOTATION, SECRET + ":older"), expected)).isTrue();
        assertThat(SecretConsumers.needsAlign(
                Map.of(SecretConsumers.CONFIG_HASH_ANNOTATION, expected), expected)).isFalse();
    }
}
