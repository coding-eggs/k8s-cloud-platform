package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.ImagePullSecretRefDTO;
import com.coding.common.models.k8s.dto.PodSpecDTO;
import com.coding.common.models.k8s.dto.PodTemplateDTO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工作负载写入时的拉取凭据兜底（spec 2026-10-31 §6.7③）。
 *
 * <p>只在「显式指定了别的 ServiceAccount」时才补 —— `default` SA 那条是命名空间级兜底，
 * 而 ServiceAccount admission 只合并 **Pod 所用那个 SA** 的 imagePullSecrets。
 */
class WorkloadServiceRegistryPullSecretTest {

    private PodTemplateDTO template(String serviceAccount, List<String> existing) {
        PodSpecDTO spec = new PodSpecDTO();
        spec.setServiceAccountName(serviceAccount);
        if (existing != null) {
            List<ImagePullSecretRefDTO> refs = new ArrayList<>();
            for (String name : existing) {
                ImagePullSecretRefDTO r = new ImagePullSecretRefDTO();
                r.setName(name);
                refs.add(r);
            }
            spec.setImagePullSecrets(refs);
        }
        PodTemplateDTO pt = new PodTemplateDTO();
        pt.setSpec(spec);
        return pt;
    }

    private List<String> names(PodTemplateDTO pt) {
        return pt.getSpec().getImagePullSecrets() == null ? List.of()
                : pt.getSpec().getImagePullSecrets().stream().map(ImagePullSecretRefDTO::getName).toList();
    }

    @Test
    void theDefaultServiceAccountNeedsNoInjection() {
        // default SA 上已经挂着平台那条，这里再写一遍是重复
        PodTemplateDTO pt = template("default", null);

        WorkloadService.ensureRegistryPullSecret(pt);

        assertThat(pt.getSpec().getImagePullSecrets()).isNull();
    }

    @Test
    void noServiceAccountMeansTheDefaultOne() {
        PodTemplateDTO pt = template(null, null);

        WorkloadService.ensureRegistryPullSecret(pt);

        assertThat(pt.getSpec().getImagePullSecrets()).isNull();
    }

    @Test
    void anotherServiceAccountGetsThePlatformSecret() {
        PodTemplateDTO pt = template("my-sa", null);

        WorkloadService.ensureRegistryPullSecret(pt);

        assertThat(names(pt)).containsExactly("platform-registry-pull");
    }

    @Test
    void whatTheUserAlreadyWroteIsKept() {
        // 合并而不是覆盖 —— 用户自己挂的别项必须原样保留
        PodTemplateDTO pt = template("my-sa", List.of("user-secret"));

        WorkloadService.ensureRegistryPullSecret(pt);

        assertThat(names(pt)).containsExactly("user-secret", "platform-registry-pull");
    }

    @Test
    void injectingTwiceChangesNothing() {
        PodTemplateDTO pt = template("my-sa", List.of("platform-registry-pull"));

        WorkloadService.ensureRegistryPullSecret(pt);

        assertThat(names(pt)).containsExactly("platform-registry-pull");
    }
}
