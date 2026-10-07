package com.coding.platformapi.services.validation;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.ContainerDTO;
import com.coding.common.models.k8s.dto.PodSpecDTO;
import com.coding.common.models.k8s.dto.PodTemplateDTO;
import com.coding.common.models.k8s.dto.WorkloadDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A6：固定 IP（Calico ipAddrs）门控——仅单副本 deployment/statefulset */
class WorkloadValidatorStaticIpTest {

    private final WorkloadValidator v = new WorkloadValidator();

    private WorkloadDTO dto(String kind, Integer replicas, List<String> staticIps) {
        ContainerDTO container = new ContainerDTO();
        container.setName("web");
        container.setImage("nginx:1.27");
        PodSpecDTO spec = new PodSpecDTO();
        spec.setContainers(List.of(container));
        PodTemplateDTO pt = new PodTemplateDTO();
        pt.setSpec(spec);
        pt.setStaticIps(staticIps);
        WorkloadDTO d = new WorkloadDTO();
        d.setKind(kind);
        d.setName("web");
        d.setNamespace("default");
        d.setReplicas(replicas);
        d.setPodTemplate(pt);
        return d;
    }

    @Test
    void deployment_single_replica_with_staticIps_passes() {
        assertThatCode(() -> v.validate(dto("deployment", 1, List.of("10.48.0.5"))))
                .doesNotThrowAnyException();
    }

    @Test
    void statefulset_replicas_null_defaults_to_one_passes() {
        assertThatCode(() -> v.validate(dto("statefulset", null, List.of("10.48.0.5"))))
                .doesNotThrowAnyException();
    }

    @Test
    void deployment_multi_replica_with_staticIps_rejected() {
        assertThatThrownBy(() -> v.validate(dto("deployment", 2, List.of("10.48.0.5"))))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("单副本");
    }

    @Test
    void daemonset_with_staticIps_rejected() {
        assertThatThrownBy(() -> v.validate(dto("daemonset", null, List.of("10.48.0.5"))))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("单副本");
    }

    @Test
    void daemonset_multi_replica_without_staticIps_passes() {
        assertThatCode(() -> v.validate(dto("daemonset", null, null)))
                .doesNotThrowAnyException();
    }
}
