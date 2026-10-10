package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.List;

/**
 * 节点健康投影（集群级）；节点名 + Ready + pressure + kubelet 版本，够概览页首屏用。
 * <p>{@code ready}：{@code status.conditions[type=Ready].status == "True"}。
 * <b>条件缺失或 Unknown 一律算 false</b>（保守：没拿到 Ready 的节点不该被当作可用）。
 * <p>{@code pressures}：{@code MemoryPressure / DiskPressure / PIDPressure} 中 status=True 的那些
 * （只列命中的类型名，具体 message 看节点详情页）。<b>Ready 的节点也可能带 pressure</b>
 * —— 它是调度提示，不是 NotReady。
 */
@Data
public class NodeHealthDTO {

    private String name;
    /** Ready 条件为 True；缺失 / False / Unknown 均为 false */
    private boolean ready;
    /** 命中的 pressure 类型（MemoryPressure / DiskPressure / PIDPressure），可空 */
    private List<String> pressures;
    /** status.nodeInfo.kubeletVersion */
    private String version;
}
