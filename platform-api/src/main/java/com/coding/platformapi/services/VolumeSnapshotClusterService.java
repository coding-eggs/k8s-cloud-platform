package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.VolumeSnapshotClassDTO;
import com.coding.common.models.k8s.dto.VolumeSnapshotContentDTO;
import com.coding.common.models.k8s.dto.VolumeSnapshotDTO;
import com.coding.platformapi.k8s.K8sClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 平台视图：快照类与快照内容（集群级，只读，仅平台管理员）。
 * <p>唯一的加工是给快照内容标「孤儿」：它指向的快照已经不在了。孤儿内容的
 * deletionPolicy=Retain 时数据还占着存储后端，管理员需要看见它。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VolumeSnapshotClusterService {

    private final K8sClient k8s;

    public List<VolumeSnapshotClassDTO> listClasses(VolumeSnapshotClassDTO query) {
        return k8s.list(query);
    }

    public VolumeSnapshotClassDTO getClass(String name, String tenantId, String clusterId) {
        VolumeSnapshotClassDTO d = new VolumeSnapshotClassDTO();
        d.setName(name);
        d.setTenantId(tenantId);
        d.setClusterId(clusterId);
        return k8s.get(d);
    }

    /**
     * 列出快照内容并标孤儿。对照用的是跨命名空间的快照全表；拿不到时 {@code orphan} 保持 null ——
     * 宁可不标，也不能把正常内容误标成孤儿。
     */
    public List<VolumeSnapshotContentDTO> listContents(VolumeSnapshotContentDTO query) {
        List<VolumeSnapshotContentDTO> contents = k8s.list(query);
        Set<String> live;
        try {
            VolumeSnapshotDTO all = new VolumeSnapshotDTO();
            all.setClusterId(query.getClusterId());
            all.setTenantId(query.getTenantId());
            live = k8s.listAll(all).stream()
                    .map(s -> key(s.getNamespace(), s.getName()))
                    .collect(Collectors.toSet());
        } catch (RuntimeException e) {
            log.warn("列快照失败，快照内容不标孤儿：clusterId={}", query.getClusterId(), e);
            return contents;
        }
        contents.forEach(c -> c.setOrphan(!live.contains(key(c.getSnapshotNamespace(), c.getSnapshotName()))));
        return contents;
    }

    public VolumeSnapshotContentDTO getContent(String name, String tenantId, String clusterId) {
        VolumeSnapshotContentDTO d = new VolumeSnapshotContentDTO();
        d.setName(name);
        d.setTenantId(tenantId);
        d.setClusterId(clusterId);
        return k8s.get(d);
    }

    private static String key(String namespace, String name) {
        return namespace + "/" + name;
    }
}
