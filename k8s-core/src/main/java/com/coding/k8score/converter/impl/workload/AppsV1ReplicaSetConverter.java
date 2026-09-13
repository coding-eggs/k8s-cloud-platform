package com.coding.k8score.converter.impl.workload;

import com.coding.common.models.k8s.dto.ReplicaSetDTO;
import com.coding.k8score.converter.CommonConverter;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;

/**
 * apps/v1 ReplicaSet ⇄ ReplicaSetDTO（只读：仅列表/查询返回；ReplicaSet 由 Deployment 管理，
 * 平台侧不支持创建/更新/删除）。revert 只回填标识字段——指标场景只需 RS 名。
 */
public class AppsV1ReplicaSetConverter implements CommonConverter<ReplicaSet, ReplicaSetDTO> {

    @Override
    public ReplicaSet convert(ReplicaSetDTO in) {
        throw new UnsupportedOperationException("ReplicaSet 由 Deployment 管理，不支持平台侧创建");
    }

    @Override
    public ReplicaSetDTO revert(ReplicaSet replicaSet) {
        if (replicaSet == null) return null;
        ReplicaSetDTO dto = new ReplicaSetDTO();
        if (replicaSet.getMetadata() != null) {
            dto.setName(replicaSet.getMetadata().getName());
            dto.setNamespace(replicaSet.getMetadata().getNamespace());
            dto.setLabels(replicaSet.getMetadata().getLabels());
        }
        return dto;
    }

}
