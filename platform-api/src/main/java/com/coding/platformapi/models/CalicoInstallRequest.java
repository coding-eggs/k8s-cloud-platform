package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Calico 的网络参数（安装/升级时在表单里填）。
 *
 * <p>⚠️ 与集群现有网络不一致会**直接断网**：CIDR 必须是该集群的 Pod CIDR，
 * 封装方式必须与集群网络方案一致。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CalicoInstallRequest {
    /** Pod CIDR（如 192.168.0.0/16） */
    private String podCidr;
    /** 封装方式：IPIP / VXLAN / IPIPCrossSubnet / VXLANCrossSubnet / None */
    private String encapsulation;
}
