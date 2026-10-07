package com.coding.k8score.converter.impl.workload;

import com.coding.common.models.k8s.dto.ContainerDTO;
import com.coding.common.models.k8s.dto.PodSpecDTO;
import com.coding.common.models.k8s.dto.PodTemplateDTO;
import com.coding.common.models.k8s.dto.WorkloadDTO;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** item 13：staticIps ⇄ pod template 注解 cni.projectcalico.org/ipAddrs 双向映射 */
class WorkloadConverterStaticIpTest {

    private final WorkloadConverter c = new WorkloadConverter();

    private WorkloadDTO dto(List<String> staticIps, Map<String, Object> tplAnnotations) {
        ContainerDTO container = new ContainerDTO();
        container.setName("web");
        container.setImage("nginx:1.27");
        PodSpecDTO spec = new PodSpecDTO();
        spec.setContainers(List.of(container));
        PodTemplateDTO pt = new PodTemplateDTO();
        pt.setAnnotations(tplAnnotations);
        pt.setStaticIps(staticIps);
        pt.setSpec(spec);
        WorkloadDTO d = new WorkloadDTO();
        d.setKind("deployment");
        d.setName("web");
        d.setNamespace("default");
        d.setReplicas(1);
        d.setPodTemplate(pt);
        return d;
    }

    @Test
    void build_staticIps_writes_ipAddrs_annotation_and_keeps_other_keys() {
        Map<String, Object> ann = new LinkedHashMap<>();
        ann.put("prometheus.io/scrape", "true");
        Deployment dep = c.convertDeployment(dto(List.of("10.48.0.5", "fd00::5"), ann));

        Map<String, String> tplAnn = dep.getSpec().getTemplate().getMetadata().getAnnotations();
        assertThat(tplAnn).containsEntry(WorkloadConverter.ANNOTATION_IP_ADDRS, "[\"10.48.0.5\",\"fd00::5\"]");
        assertThat(tplAnn).containsEntry("prometheus.io/scrape", "true");
    }

    @Test
    void build_no_staticIps_removes_stale_ipAddrs_from_annotations() {
        Map<String, Object> ann = new LinkedHashMap<>();
        ann.put(WorkloadConverter.ANNOTATION_IP_ADDRS, "[\"10.48.0.9\"]"); // 客户端误传/残留
        ann.put("keep", "me");
        Deployment dep = c.convertDeployment(dto(null, ann));

        Map<String, String> tplAnn = dep.getSpec().getTemplate().getMetadata().getAnnotations();
        assertThat(tplAnn).doesNotContainKey(WorkloadConverter.ANNOTATION_IP_ADDRS);
        assertThat(tplAnn).containsEntry("keep", "me");
    }

    @Test
    void build_empty_staticIps_writes_no_annotation() {
        Deployment dep = c.convertDeployment(dto(List.of(), null));
        assertThat(dep.getSpec().getTemplate().getMetadata().getAnnotations()).isNull();
    }

    @Test
    void revert_ipAddrs_annotation_maps_to_staticIps_and_is_removed_from_annotations() {
        WorkloadDTO d = dto(null, null);
        Deployment dep = c.convertDeployment(d);
        // 模拟集群侧已有注解（build 已写入）
        Map<String, String> tplAnn = new LinkedHashMap<>(dep.getSpec().getTemplate().getMetadata().getAnnotations());
        tplAnn.put("other", "x");
        dep.getSpec().getTemplate().getMetadata().setAnnotations(tplAnn);

        WorkloadDTO back = c.revert(dep);
        assertThat(back.getPodTemplate().getStaticIps()).containsExactly("10.48.0.5", "fd00::5");
        assertThat(back.getPodTemplate().getAnnotations())
                .doesNotContainKey(WorkloadConverter.ANNOTATION_IP_ADDRS)
                .containsEntry("other", "x");
    }

    @Test
    void revert_malformed_ipAddrs_keeps_raw_annotation_and_leaves_staticIps_null() {
        WorkloadDTO d = dto(null, null);
        Deployment dep = c.convertDeployment(d);
        Map<String, String> tplAnn = new LinkedHashMap<>();
        tplAnn.put(WorkloadConverter.ANNOTATION_IP_ADDRS, "not-a-json-array");
        dep.getSpec().getTemplate().getMetadata().setAnnotations(tplAnn);

        WorkloadDTO back = c.revert(dep);
        assertThat(back.getPodTemplate().getStaticIps()).isNull();
        assertThat(back.getPodTemplate().getAnnotations())
                .containsEntry(WorkloadConverter.ANNOTATION_IP_ADDRS, "not-a-json-array");
    }

    @Test
    void roundTrip_staticIps_survives_build_then_revert() {
        WorkloadDTO back = c.revert(c.convertDeployment(dto(List.of("192.168.1.10"), null)));
        assertThat(back.getPodTemplate().getStaticIps()).containsExactly("192.168.1.10");
    }
}
