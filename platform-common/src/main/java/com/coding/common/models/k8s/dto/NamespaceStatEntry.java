package com.coding.common.models.k8s.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 明细行（一个命名空间的切片）+ 所属集群 + 配额三档状态。
 * <p>集群概览「资源明细」tab 与租户概览明细共用：{@code clusterId} 由 platform-api 合并时回填
 * （k8s-server 侧的单 ns 端点不知道集群 id 之外的范围），{@code quotaState} 由 platform-api 的
 * {@code QuotaClassifier} 判定后回带 —— 前端不再自己按前缀判「有没有配额」。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NamespaceStatEntry {

    private String clusterId;
    private NamespaceSnapshotDTO snapshot;
    private QuotaState quotaState;
}
