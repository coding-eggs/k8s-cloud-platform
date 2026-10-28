package com.coding.data.models.k8s;

import java.sql.Date;

import lombok.Data;

/**
 * K8s集群信息表
 * k8s_cluster
 */
@Data
public class K8sCluster {
    /**
     * 集群id
     */
    private String clusterId;

    /**
     * 集群名称
     */
    private String clusterName;

    /**
     * .kube/config 文件
     */
    private String kubeconfig;

    /**
     * 集群版本
     */
    private String version;

    /**
     * istio version
     */
    private String istioVersion;

    /**
     * calico version
     */
    private String calicoVersion;

    /**
     * 容器运行时
     */
    private String containerRuntime;

    /**
     * Prometheus 地址（仅记录，本监控功能不用）
     */
    private String prometheusUrl;

    /**
     * Grafana 地址（仅记录）
     */
    private String grafanaUrl;

    /**
     * 平台访问该集群对象存储（S3）的地址，例如 http://192.168.31.80:38333。
     *
     * <p>空 = 按组件安装位置推导集群内地址（见 {@code ComponentRecipeCatalog}）。
     * 只有平台够不到该集群的 ClusterIP 时（平台在集群外、或业务集群）才需要填。
     */
    private String objectStorageUrl;

    /**
     * 镜像仓库地址（{@code host[:端口]}，不带 scheme）：空 = 用平台默认
     * （{@code platform.image-registry.host}）或 Harbor 组件的安装记录。
     */
    private String imageRegistryHost;

    /** 镜像仓库项目（默认 platform）：空 = 用平台默认 */
    private String imageRegistryProject;

    /**
     * 集群描述
     */
    private String description;

    /**
     * IP栈类型：IPV4/IPV6/IPV4_AND_IPV6
     */
    private IPStack ipStack;

    /**
     * 状态：CONNECTED/DISCONNECTED/CONNECTING/ERROR
     */
    private ClusterStatus status;

    /**
     * 是否开启, 0 关闭，1 开启
     */
    private Integer enabled;

    /**
     * 最后一次心跳时间
     */
    private Date lastHeartbeatTime;

    /**
     * 创建时间
     */
    private Date createdAt;

    /**
     * 更新时间
     */
    private Date updatedAt;

    /**
     * 软删除时间，NULL = 未删除
     */
    private Date deletedAt;

    /**
     * 集群 API 能力（JSON：group → versions[]），运行时 discovery 探测后持久化
     */
    private String capability;

    /**
     * 集群画像：MANAGEMENT=平台自身所在集群（多装 Thanos 等底座）；BUSINESS=业务集群（默认）。
     * 见 docs/superpowers/specs/2026-10-17-component-lifecycle-design.md §2.1
     */
    private String clusterRole;


    public enum IPStack {
        IPV4, IPV6 ,IPV4_AND_IPV6
    }

    public enum ClusterStatus {
        CONNECTED,          // 正常连接
        DISCONNECTED,       // 连接失败
        CONNECTING,         // 正在连接中
        ERROR               // 其他错误
    }
}