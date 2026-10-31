package com.coding.k8score.converter.impl.rbac;

import com.coding.common.models.k8s.dto.ImagePullSecretRefDTO;
import com.coding.common.models.k8s.dto.ServiceAccountDTO;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ServiceAccount 的 imagePullSecrets 双向映射。
 *
 * <p>为什么这个字段值得单独钉：平台靠它把租户的 registry 凭据挂到命名空间的 {@code default} 上
 * （spec 2026-10-31 §6）。少映射一半的后果是"Secret 建了、SA 没挂上"，而现象是
 * {@code ImagePullBackOff} —— 看起来完全像凭据错。
 */
class CoreV1ServiceAccountConverterTest {

    private final CoreV1ServiceAccountConverter converter = new CoreV1ServiceAccountConverter();

    @Test
    void imagePullSecretsSurviveTheRoundTrip() {
        ServiceAccountDTO dto = new ServiceAccountDTO();
        dto.setName("default");
        dto.setNamespace("app-a");
        ImagePullSecretRefDTO ref = new ImagePullSecretRefDTO();
        ref.setName("platform-registry-pull");
        dto.setImagePullSecrets(List.of(ref));

        ServiceAccount sa = converter.convert(dto);

        assertThat(sa.getImagePullSecrets()).extracting(r -> r.getName())
                .containsExactly("platform-registry-pull");
        assertThat(converter.revert(sa).getImagePullSecrets()).extracting(ImagePullSecretRefDTO::getName)
                .containsExactly("platform-registry-pull");
    }

    @Test
    void aNullListNeverReachesTheWire() {
        // fabric8 的这个字段默认是空 ArrayList 且标了 NON_EMPTY —— 序列化时会被整个省掉，
        // 效果等同"不碰这个字段"（SSA 下不会去删别人挂的条目）。
        ServiceAccountDTO dto = new ServiceAccountDTO();
        dto.setName("default");

        ServiceAccount sa = converter.convert(dto);
        assertThat(sa.getImagePullSecrets()).isEmpty();
        assertThat(Serialization.asJson(sa)).doesNotContain("imagePullSecrets");
    }

    @Test
    void anEmptyListIsAlsoOmitted() {
        ServiceAccountDTO dto = new ServiceAccountDTO();
        dto.setName("default");
        dto.setImagePullSecrets(List.of());

        assertThat(Serialization.asJson(converter.convert(dto))).doesNotContain("imagePullSecrets");
    }
}
