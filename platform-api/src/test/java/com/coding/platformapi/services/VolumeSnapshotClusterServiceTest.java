package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.VolumeSnapshotContentDTO;
import com.coding.common.models.k8s.dto.VolumeSnapshotDTO;
import com.coding.platformapi.k8s.K8sClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 平台只读：快照内容的「孤儿」标记。对照失败时宁可不标，也不能把正常内容误标成孤儿。 */
class VolumeSnapshotClusterServiceTest {

    private final K8sClient k8s = mock(K8sClient.class);
    private final VolumeSnapshotClusterService svc = new VolumeSnapshotClusterService(k8s);

    private static VolumeSnapshotContentDTO content(String name, String ns, String snapshot) {
        VolumeSnapshotContentDTO c = new VolumeSnapshotContentDTO();
        c.setName(name);
        c.setSnapshotNamespace(ns);
        c.setSnapshotName(snapshot);
        return c;
    }

    private static VolumeSnapshotDTO snapshot(String ns, String name) {
        VolumeSnapshotDTO s = new VolumeSnapshotDTO();
        s.setNamespace(ns);
        s.setName(name);
        return s;
    }

    private static VolumeSnapshotContentDTO query() {
        VolumeSnapshotContentDTO q = new VolumeSnapshotContentDTO();
        q.setClusterId("c1");
        return q;
    }

    @Test
    void contentIsOrphanWhenSnapshotGone() {
        when(k8s.list(any(VolumeSnapshotContentDTO.class))).thenReturn(List.of(
                content("c-live", "ns1", "snap"),
                content("c-gone", "ns1", "deleted"),
                content("c-other-ns", "ns2", "snap")));
        when(k8s.listAll(any(VolumeSnapshotDTO.class))).thenReturn(List.of(snapshot("ns1", "snap")));

        List<VolumeSnapshotContentDTO> out = svc.listContents(query());

        assertThat(out).extracting(VolumeSnapshotContentDTO::getOrphan).containsExactly(false, true, true);
    }

    @Test
    void listAllFailureLeavesOrphanNull() {
        when(k8s.list(any(VolumeSnapshotContentDTO.class))).thenReturn(List.of(content("c", "ns1", "snap")));
        when(k8s.listAll(any(VolumeSnapshotDTO.class))).thenThrow(new CloudPlatformException(500, "boom"));

        List<VolumeSnapshotContentDTO> out = svc.listContents(query());

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getOrphan()).isNull();
    }
}
