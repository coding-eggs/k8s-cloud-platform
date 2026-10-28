package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** {@code POST /cluster/components/apply} 的请求体。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ComponentApplyRequest {
    private String clusterId;
    private String componentCode;
    /** INSTALL / UPGRADE */
    private String action;
    /** 目标 recipe 版本（空 = 用 recipe 的当前版本） */
    private String targetVersion;
    /** 用户可编辑的 values（只允许 recipe 声明的字段） */
    private Map<String, Object> editableValues;
    /**
     * 平台告警回调地址（仅 platformAlerting 的组件用，如 Prometheus）。
     * 用户在建/升级时填写；clusterId 由后端拼接。空 = 用平台配置的默认值。
     */
    private String alertWebhookUrl;

    /**
     * Thanos Receive 地址（仅 thanosRemoteWrite 的组件用，如 Prometheus）。
     * 用户在建/升级时填写；空 = 用平台配置 {@code thanos.receive.url}。
     */
    private String thanosReceiveUrl;

    /** Thanos 安装参数（仅 thanosInstall 的组件用，管理集群的 Prometheus） */
    private ThanosInstallRequest thanosInstall;

    /** Calico 网络参数（仅 calicoNetwork 的组件用：Pod CIDR / 封装方式） */
    private CalicoInstallRequest calicoInstall;

    /**
     * Tekton 的 profile（仅 tektonProfile 的组件用：lite / basic / all）。
     * 空 = 沿用上次成功操作里的值，再空 = 默认 all。
     */
    private String tektonProfile;

    /** OTel 采集栈参数（仅 otel-operator 组件用） */
    private OtelInstallRequest otel;

    /** 对象存储参数（仅 seaweedfs 组件用：StorageClass / 容量 / 副本数） */
    private SeaweedInstallRequest seaweed;

    /** Harbor 安装参数（仅 harborRegistry 的组件：访问域名） */
    private HarborInstallRequest harbor;

    /** 兼容旧调用（不含告警地址与 Thanos 地址） */
    public ComponentApplyRequest(String clusterId, String componentCode, String action,
                                 String targetVersion, Map<String, Object> editableValues) {
        this(clusterId, componentCode, action, targetVersion, editableValues, null, null, null, null, null, null,
                null, null);
    }

    /** 兼容只带告警地址的调用 */
    public ComponentApplyRequest(String clusterId, String componentCode, String action,
                                 String targetVersion, Map<String, Object> editableValues,
                                 String alertWebhookUrl) {
        this(clusterId, componentCode, action, targetVersion, editableValues, alertWebhookUrl,
                null, null, null, null, null, null, null);
    }

    /** 兼容带两个地址的调用 */
    public ComponentApplyRequest(String clusterId, String componentCode, String action,
                                 String targetVersion, Map<String, Object> editableValues,
                                 String alertWebhookUrl, String thanosReceiveUrl) {
        this(clusterId, componentCode, action, targetVersion, editableValues,
                alertWebhookUrl, thanosReceiveUrl, null, null, null, null, null, null);
    }

    /** 兼容带 Thanos 安装参数的调用 */
    public ComponentApplyRequest(String clusterId, String componentCode, String action,
                                 String targetVersion, Map<String, Object> editableValues,
                                 String alertWebhookUrl, String thanosReceiveUrl,
                                 ThanosInstallRequest thanosInstall) {
        this(clusterId, componentCode, action, targetVersion, editableValues,
                alertWebhookUrl, thanosReceiveUrl, thanosInstall, null, null, null, null, null);
    }

    /** 兼容带 Calico 参数的调用（不含 tektonProfile） */
    public ComponentApplyRequest(String clusterId, String componentCode, String action,
                                 String targetVersion, Map<String, Object> editableValues,
                                 String alertWebhookUrl, String thanosReceiveUrl,
                                 ThanosInstallRequest thanosInstall, CalicoInstallRequest calicoInstall) {
        this(clusterId, componentCode, action, targetVersion, editableValues,
                alertWebhookUrl, thanosReceiveUrl, thanosInstall, calicoInstall, null, null, null, null);
    }

    /** 兼容带 OTel / 对象存储参数的调用（不含 harbor） */
    public ComponentApplyRequest(String clusterId, String componentCode, String action,
                                 String targetVersion, Map<String, Object> editableValues,
                                 String alertWebhookUrl, String thanosReceiveUrl,
                                 ThanosInstallRequest thanosInstall, CalicoInstallRequest calicoInstall,
                                 String tektonProfile, OtelInstallRequest otel,
                                 SeaweedInstallRequest seaweed) {
        this(clusterId, componentCode, action, targetVersion, editableValues,
                alertWebhookUrl, thanosReceiveUrl, thanosInstall, calicoInstall, tektonProfile, otel,
                seaweed, null);
    }

    /**
     * 镜像来源：{@code PLATFORM}（默认，平台 Harbor）/ {@code OFFICIAL}（上游官方）。
     *
     * <p>只对「平台渲染出来的镜像」生效；harbor 组件本身永远走官方（先有鸡还是先有蛋）。
     */
    private String imageSource;

    /**
     * 13 参构造（与 {@code @AllArgsConstructor} 相比只少 {@code imageSource}）：老调用方不必改，
     * 镜像来源默认 {@code null} = PLATFORM。
     */
    public ComponentApplyRequest(String clusterId, String componentCode, String action,
                                 String targetVersion, Map<String, Object> editableValues,
                                 String alertWebhookUrl, String thanosReceiveUrl,
                                 ThanosInstallRequest thanosInstall, CalicoInstallRequest calicoInstall,
                                 String tektonProfile, OtelInstallRequest otel,
                                 SeaweedInstallRequest seaweed, HarborInstallRequest harbor) {
        this(clusterId, componentCode, action, targetVersion, editableValues, alertWebhookUrl,
                thanosReceiveUrl, thanosInstall, calicoInstall, tektonProfile, otel, seaweed, harbor, null);
    }
}
