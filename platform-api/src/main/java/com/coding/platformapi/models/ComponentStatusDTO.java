package com.coding.platformapi.models;

import lombok.Data;

import java.util.List;

/**
 * 一条组件的完整状态（平台侧投影）。
 *
 * <p>⚠️ {@code state} 是<b>四态</b>，不是布尔：{@code INSTALLED_HEALTHY} / {@code INSTALLED_UNHEALTHY} /
 * {@code NOT_INSTALLED} / {@code UNKNOWN}。{@code UNKNOWN}（探测失败 / 未探测）与
 * {@code NOT_INSTALLED}（探测成功但没命中）是相反结论，前端不得合并渲染。
 */
@Data
public class ComponentStatusDTO {
    private String code;
    private String name;
    /** 四态之一，见类注释 */
    private String state;
    /** 实测版本（app.kubernetes.io/version 标签或 image tag）；只有 CRD 判据 / 未命中 → null */
    private String version;
    /** 命中的工作负载所在命名空间 */
    private String namespace;
    private Integer ready;
    private Integer desired;
    /** 人读的一句话：命中数 / "n/m 就绪" / "在平台已知的位置没找到" */
    private String detail;
    /** 它支撑的平台功能 code（依赖关系只在目录里写一遍，这里是派生出来的） */
    private List<String> features;
    /**
     * 平台**可安装**的 chart 版本（来自 recipe；空 = 没有 recipe，不支持安装）。
     * ⚠️ 这是 chart 版本，不是上面 {@code version} 的应用版本 —— 两套编号。
     */
    private List<String> availableVersions;
    /**
     * 组件包含的**每一个 release** 的 chart / 应用版本（含主 release 标记）。
     * 单 release 组件只有一条；Istio 这类多 chart 安装单元按安装顺序列全。
     */
    private List<ComponentReleaseDTO> releases;
    /** 可编辑配置项（⚙️，来自 recipe）；契约值（🔒）不在其中 */
    private List<EditableFieldDTO> editableFields;
    /** 安装/升级成功后是否下发平台告警联动（Secret + AlertmanagerConfig） */
    private boolean platformAlerting;
    /**
     * 告警回调地址的**平台默认值**（前端表单预填用）。
     * 用户可在安装表单里改；clusterId 永远由后端拼。
     */
    private String defaultAlertWebhookUrl;
    /**
     * **当前生效**的告警回调地址（上次成功操作的 {@code __platform} 快照）。
     * 编辑表单用它预填 —— 用户要能看到现在用的是哪个地址；无记录 → null。
     */
    private String alertWebhookUrl;
    /** 该组件安装时是否需要填写 Thanos Receive 地址（Prometheus 的 remoteWrite） */
    private boolean thanosRemoteWrite;
    /** Thanos Receive 地址的平台默认值（表单预填用） */
    private String defaultThanosReceiveUrl;
    /** **当前生效**的 Thanos Receive 地址（上次成功操作的快照）；无记录 → null */
    private String thanosReceiveUrl;
    /** 该组件安装时是否同时安装 Thanos（管理集群的 Prometheus：表单要选命名空间/凭证 Secret、填 MinIO） */
    private boolean thanosInstall;
    /**
     * **当前生效**的 Thanos 安装参数（上次成功操作的 {@code __platform.thanosInstall}）。
     * 编辑表单预填：凭证 Secret、MinIO 地址/桶、跳过 HTTPS 校验都能在装完后改。
     */
    private ThanosInstallRequest thanosInstallCurrent;
    /** 安装时是否要填 Calico 的 Pod CIDR / 封装方式 */
    private boolean calicoNetwork;
    /** Calico 默认值（表单预填） */
    private String defaultPodCidr;
    private String defaultEncapsulation;
    /** 安装时是否要选 Tekton profile（lite / basic / all） */
    private boolean tektonProfile;
    /** Tekton profile 的默认值（表单预填） */
    private String defaultTektonProfile;
    /** **当前生效**的 Tekton profile（上次成功操作快照）；无记录 → null */
    private String tektonProfileCurrent;

    /** 安装时是否要暴露「语言 / 追踪 / 日志」表单（OTel 采集栈） */
    private boolean otelInstrumentation;
    /** 安装时是否同时安装 Tempo（管理集群，表单要填 MinIO 地址/桶/凭证） */
    private boolean tempoInstall;
    /** 安装时是否要填 Tempo 地址（collector 的 traces 导出目标） */
    private boolean tempoRemoteWrite;
    /** Tempo 地址的平台默认值（表单预填用） */
    private String defaultTempoEndpoint;
    /** **当前生效**的 Tempo 地址（上次成功操作快照）；无记录 → null */
    private String tempoEndpoint;
    /** **当前生效**的 OTel 安装参数（上次成功操作快照）；无记录 → null */
    private OtelInstallRequest otelInstallCurrent;

    /** 安装时是否要选对象存储（SeaweedFS）的 StorageClass / 容量 / 副本数 */
    private boolean seaweedStorage;
    /** **当前生效**的对象存储参数（上次成功操作的 {@code __platform.seaweed}）；无记录 → null */
    private SeaweedInstallRequest seaweedCurrent;
}
