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

import java.sql.Date;
import java.util.List;

/**
 * 集群管理：kubeconfig 加密入库 + 连通性测试 + K8s 侧开通
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClusterService {

    private final K8sClusterMapper clusterMapper;
    private final K8sAdminClient adminClient;

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
        return sanitize(clusterMapper.selectByPrimaryKey(cluster.getClusterId()));
    }

    public List<K8sCluster> list() {
        return clusterMapper.listAll().stream().map(this::sanitize).toList();
    }

    public K8sCluster get(ClusterKeyRequest req) {
        return sanitize(require(req.getClusterId()));
    }

    public K8sCluster update(ClusterUpdateRequest req) {
        require(req.getClusterId());
        K8sCluster upd = new K8sCluster();
        upd.setClusterId(req.getClusterId());
        upd.setClusterName(req.getClusterName());
        upd.setDescription(req.getDescription());
        upd.setUpdatedAt(new Date(System.currentTimeMillis()));
        clusterMapper.updateByPrimaryKeySelective(upd);
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
    }

    /**
     * 重新执行 K8s 侧开通（幂等）：创建失败重试 / 集群数据面重建后恢复
     */
    public K8sCluster provision(ClusterKeyRequest req) {
        requireEnabled(req.getClusterId());
        adminClient.provisionCluster(req.getClusterId());
        return sanitize(clusterMapper.selectByPrimaryKey(req.getClusterId()));
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
