package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.utils.AESUtils;
import com.coding.common.utils.ULIDGenerator;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.platformapi.k8s.K8sAdminClient;
import com.coding.platformapi.models.ClusterCreateRequest;
import com.coding.platformapi.models.ClusterKeyRequest;
import com.coding.platformapi.models.ClusterToggleRequest;
import com.coding.platformapi.models.ClusterUpdateRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Date;
import java.util.List;
import java.util.Map;

/**
 * 集群管理：kubeconfig 加密入库 + 连通性测试 + K8s 侧开通
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClusterService {

    private final K8sClusterMapper clusterMapper;
    private final K8sAdminClient adminClient;
    private final JsonMapper jsonMapper;

    @Value("${k8s.cloud.kubeconfig.aes-key:daXs1znnIStfQCVFyC8cvuS9OQZRTgeBJLLrrvu/hUM=}")
    private String AES_KEY;

    public K8sCluster create(ClusterCreateRequest req) {
        if (!StringUtils.hasText(req.getClusterName())) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "集群名称不能为空");
        }
        if (!StringUtils.hasText(req.getKubeconfig())) {
            throw new CloudPlatformException(EnumResponseType.NON_KUBE_CONFIG);
        }

        // 连通性测试（经 k8s-server 探测，临时 client 测完即关）
        adminClient.probe(req.getKubeconfig());

        Date now = new Date(System.currentTimeMillis());
        K8sCluster cluster = new K8sCluster();
        cluster.setClusterId(ULIDGenerator.generateULID());
        cluster.setClusterName(req.getClusterName());
        cluster.setKubeconfig(AESUtils.encrypt(req.getKubeconfig(), AES_KEY));
        cluster.setDescription(req.getDescription());
        cluster.setVersion(req.getVersion());
        cluster.setIstioVersion(req.getIstioVersion());
        cluster.setCalicoVersion(req.getCalicoVersion());
        cluster.setContainerRuntime(req.getContainerRuntime());
        // ip_stack NOT NULL 且无默认值：不填则默认 IPV4，避免插入失败
        cluster.setIpStack(req.getIpStack() != null ? req.getIpStack() : K8sCluster.IPStack.IPV4);
        cluster.setPrometheusUrl(req.getPrometheusUrl());
        cluster.setGrafanaUrl(req.getGrafanaUrl());
        cluster.setStatus(K8sCluster.ClusterStatus.CONNECTED);
        cluster.setEnabled(1);
        cluster.setCreatedAt(now);
        cluster.setUpdatedAt(now);
        clusterMapper.insert(cluster);

        try {
            adminClient.provisionCluster(cluster.getClusterId());
        } catch (Exception e) {
            // 开通失败：标记 ERROR，可通过 /cluster/provision 幂等重试
            K8sCluster err = new K8sCluster();
            err.setClusterId(cluster.getClusterId());
            err.setStatus(K8sCluster.ClusterStatus.ERROR);
            clusterMapper.updateByPrimaryKeySelective(err);
            log.error("集群 {} K8s 侧开通失败", cluster.getClusterId(), e);
            if (e instanceof CloudPlatformException cpe) {
                throw cpe;
            }
            throw new CloudPlatformException(EnumResponseType.ERROR, "集群 K8s 侧开通失败: " + e.getMessage());
        }
        // 开通成功后刷新 API 能力（best-effort，失败不阻塞创建）
        refreshCapabilityQuietly(cluster.getClusterId());
        return sanitize(clusterMapper.selectByPrimaryKey(cluster.getClusterId()));
    }

    public List<K8sCluster> list() {
        return clusterMapper.listAll().stream().map(this::sanitize).toList();
    }

    public K8sCluster get(ClusterKeyRequest req) {
        return sanitize(require(req.getClusterId()));
    }

    public K8sCluster update(ClusterUpdateRequest req) {
        K8sCluster old = require(req.getClusterId());
        K8sCluster upd = new K8sCluster();
        upd.setClusterId(req.getClusterId());

        // kubeconfig：留空 = 保留原值；填写 = 重新探测 + 重新加密
        boolean kubeconfigChanged = false;
        if (StringUtils.hasText(req.getKubeconfig())) {
            adminClient.probe(req.getKubeconfig());
            upd.setKubeconfig(AESUtils.encrypt(req.getKubeconfig(), AES_KEY));
            // AES 每次随机 salt/IV，密文不可直接比对；解密旧值与新明文比对判定是否变化
            String oldPlain = old.getKubeconfig() != null ? AESUtils.decrypt(old.getKubeconfig(), AES_KEY) : null;
            kubeconfigChanged = !req.getKubeconfig().equals(oldPlain);
        }

        upd.setClusterName(req.getClusterName());
        upd.setDescription(req.getDescription());
        upd.setVersion(req.getVersion());
        upd.setIstioVersion(req.getIstioVersion());
        upd.setCalicoVersion(req.getCalicoVersion());
        upd.setContainerRuntime(req.getContainerRuntime());
        upd.setIpStack(req.getIpStack());
        upd.setPrometheusUrl(req.getPrometheusUrl());
        upd.setGrafanaUrl(req.getGrafanaUrl());
        upd.setUpdatedAt(new Date(System.currentTimeMillis()));
        clusterMapper.updateByPrimaryKeySelective(upd);
        // kubeconfig 变化 → 旧连接作废，显式失效缓存（不依赖下面的能力探测是否成功）；能力可能变则附带重探
        if (kubeconfigChanged) {
            evictClientQuietly(req.getClusterId());
            refreshCapabilityQuietly(req.getClusterId());
        }
        return sanitize(clusterMapper.selectByPrimaryKey(req.getClusterId()));
    }

    public K8sCluster toggleEnabled(ClusterToggleRequest req) {
        require(req.getClusterId());
        if (req.getEnabled() == null || (req.getEnabled() != 0 && req.getEnabled() != 1)) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "enabled 只能为 0 或 1");
        }
        K8sCluster upd = new K8sCluster();
        upd.setClusterId(req.getClusterId());
        upd.setEnabled(req.getEnabled());
        upd.setUpdatedAt(new Date(System.currentTimeMillis()));
        clusterMapper.updateByPrimaryKeySelective(upd);
        log.info("集群 {} 启用状态变更为 {}", req.getClusterId(), req.getEnabled());
        if (req.getEnabled() == 1) {
            // 重新启用 → API 能力可能变，重新探测（best-effort；内部会 evict+重建 admin client）
            refreshCapabilityQuietly(req.getClusterId());
        } else {
            // 禁用 → 清缓存让旧 admin/tenant client 作废（getAdminClient 命中缓存不查 enabled，不清则禁用后仍可操作）
            evictClientQuietly(req.getClusterId());
        }
        return sanitize(clusterMapper.selectByPrimaryKey(req.getClusterId()));
    }

    /**
     * 软删除（K8s 侧对象保留，如需彻底清理后续由运维处理）
     */
    public void delete(ClusterKeyRequest req) {
        require(req.getClusterId());
        K8sCluster upd = new K8sCluster();
        upd.setClusterId(req.getClusterId());
        upd.setDeletedAt(new Date(System.currentTimeMillis()));
        upd.setUpdatedAt(new Date(System.currentTimeMillis()));
        clusterMapper.updateByPrimaryKeySelective(upd);
        // 软删 → 清缓存，避免已删集群的 client 常驻内存（惰性重建不查 deletedAt）
        evictClientQuietly(req.getClusterId());
    }

    /**
     * 重新执行 K8s 侧开通（幂等）：创建失败重试 / 集群数据面重建后恢复
     */
    public K8sCluster provision(ClusterKeyRequest req) {
        requireEnabled(req.getClusterId());
        adminClient.provisionCluster(req.getClusterId());
        return sanitize(clusterMapper.selectByPrimaryKey(req.getClusterId()));
    }

    /**
     * 刷新集群 API 能力：k8s-server 探测（getApiGroups）→ 序列化 JSON → 写 k8s_cluster.capability。
     */
    private void doRefresh(String clusterId) {
        Map<String, List<String>> capability = adminClient.refreshCapability(clusterId);
        K8sCluster upd = new K8sCluster();
        upd.setClusterId(clusterId);
        upd.setCapability(jsonMapper.writeValueAsString(capability));
        upd.setUpdatedAt(new Date(System.currentTimeMillis()));
        clusterMapper.updateByPrimaryKeySelective(upd);
        log.info("集群 {} API 能力已刷新（{} 个 group）", clusterId, capability.size());
    }

    /**best-effort：失败仅告警、不阻塞主流程，能力保持旧值（create / update-kubeconfig 变化 / enable 自动触发用） */
    public void refreshCapabilityQuietly(String clusterId) {
        try {
            doRefresh(clusterId);
        } catch (Exception e) {
            log.warn("集群 {} API 能力刷新失败（保持旧值）：{}", clusterId, e.getMessage());
        }
    }

    /**严格：异常透出给调用方（手动「刷新集群能力」按钮用） */
    public void refreshCapabilityStrict(String clusterId) {
        doRefresh(clusterId);
    }

    /**
     * 读集群 API 能力（k8s_cluster.capability 列，JSON：group→versions[]）。
     * 无行/空列/解析失败 → 空 map（前端据此判「未探测」，不硬阻断）。零推导：只读+反序列化。
     * 语义对齐 KubernetesOperationsFactory.readApiVersions。
     */
    public Map<String, List<String>> getCapability(String clusterId) {
        K8sCluster cluster = clusterMapper.selectByPrimaryKey(clusterId);
        if (cluster == null || !StringUtils.hasText(cluster.getCapability())) {
            return Map.of();
        }
        try {
            return jsonMapper.readValue(cluster.getCapability(),
                    new TypeReference<Map<String, List<String>>>() {});
        } catch (Exception e) {
            log.warn("解析集群 {} capability 失败（按未探测处理）: {}", clusterId, e.getMessage());
            return Map.of();
        }
    }

    /**best-effort 失效 k8s-server 侧 client 缓存：失败仅告警、不阻塞主流程（缓存惰性，下次访问/重启后自愈） */
    private void evictClientQuietly(String clusterId) {
        try {
            adminClient.evictClusterClient(clusterId);
        } catch (Exception e) {
            log.warn("集群 {} client 缓存失效失败（下次访问/重启后自愈）：{}", clusterId, e.getMessage());
        }
    }

    private K8sCluster require(String clusterId) {
        K8sCluster cluster = clusterMapper.selectByPrimaryKey(clusterId);
        if (cluster == null || cluster.getDeletedAt() != null) {
            throw new CloudPlatformException(EnumResponseType.CLUSTER_NOT_EXIST);
        }
        return cluster;
    }

    private K8sCluster requireEnabled(String clusterId) {
        K8sCluster cluster = require(clusterId);
        if (cluster.getEnabled() != 1) {
            throw new CloudPlatformException(EnumResponseType.CLUSTER_DISABLED);
        }
        return cluster;
    }

    /**
     * 对外不暴露 kubeconfig（即使是密文也不下发）
     */
    private K8sCluster sanitize(K8sCluster cluster) {
        cluster.setKubeconfig(null);
        return cluster;
    }
}
