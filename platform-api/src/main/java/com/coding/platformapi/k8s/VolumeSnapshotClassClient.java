package com.coding.platformapi.k8s;

import com.coding.common.models.k8s.dto.VolumeSnapshotClassDTO;
import com.coding.common.models.k8s.dto.VolumeSnapshotClassOptionsDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * k8s-server 快照类窄投影 client（{@code POST /volumesnapshotclasses/options}，UPSTREAM 边界）。纯传输零业务。
 * <p>平台视图读完整快照类走 {@link K8sClient}（DTO 驱动的六端点）。
 */
@Component
@RequiredArgsConstructor
public class VolumeSnapshotClassClient {

    private final K8sServerGateway gateway;

    /** 快照 CRD 是否已装 + 快照类窄投影。只有 404 会变成 crdInstalled=false，其它失败照常抛出。 */
    public VolumeSnapshotClassOptionsDTO options(String clusterId) {
        VolumeSnapshotClassDTO body = new VolumeSnapshotClassDTO();
        body.setClusterId(clusterId);
        return gateway.exchange(HttpMethod.POST, "/volumesnapshotclasses/options", null, body,
                gateway.responseType(VolumeSnapshotClassOptionsDTO.class));
    }
}
