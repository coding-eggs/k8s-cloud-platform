package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对象存储（SeaweedFS）的安装参数（安装/升级时在表单里填）。
 *
 * <p>为什么不用 {@code editableValues}：这些值落在 chart 的**嵌套结构**里 ——
 * {@code volume.dataDirs} 是 list、{@code master.data}/{@code filer.data} 是 map，
 * 而 editableValues 的模型是「路径 → 标量」，表达不了。其余组件（Prometheus/Thanos）的
 * 存储字段是标量路径，所以那边能用 editableValues。
 *
 * <p>⚠️ {@code storageClass} 必填：chart 模板无条件渲染 {@code storageClassName: {{ .Values.*.storageClass }}}，
 * 空串在 K8s 里表示「不用动态供给」（不是「跟随集群默认」），会装出一组永远 Pending 的 PVC。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeaweedInstallRequest {
    /** 四个组件共用的 StorageClass（必填，来自集群 StorageClass 下拉） */
    private String storageClass;
    /** master 的 raft 日志 / 快照容量（集群身份存在这里，必须持久化），默认 1Gi */
    private String masterSize;
    /** 每个 volume server 的数据盘容量，默认 30Gi */
    private String volumeSize;
    /** filer 元数据容量，默认 10Gi */
    private String filerSize;
    /** master 副本数（Raft 要奇数；默认 1 = 开发/测试，HA 用 3） */
    private Integer masterReplicas;
    /** volume server 副本数，默认 1 */
    private Integer volumeReplicas;
    /** S3 网关副本数，默认 1 */
    private Integer s3Replicas;
    /**
     * 是否安装 admin 管理台（chart 的 {@code admin} 组件，端口 23646）。
     *
     * <p>默认不装：多一个管理面 + 一块数据盘。装的时候**必须带鉴权** —— admin 从 4.46 起
     * 绑定非 loopback 地址（0.0.0.0）时没有密码会直接退出，所以平台会先生成一个随机口令的
     * Secret（{@code <release 命名空间>/platform-seaweedfs-admin}）再引用它，口令不经过 values/快照。
     */
    private Boolean adminEnabled;
}
