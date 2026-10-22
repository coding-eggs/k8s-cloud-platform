package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import com.coding.common.models.middleware.MiddlewareScaleRequestDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MySQL 适配器（Percona Operator for MySQL，`ps.percona.com/v1`）。
 *
 * <p>两种形态都用 **Group Replication**（单主、强一致）：
 * <ul>
 *   <li>SINGLE → {@code size: 1}（单成员组，无容错，开发/测试）；</li>
 *   <li>CLUSTER → {@code size: 3/5}（≥3 成员才有多数派）。</li>
 * </ul>
 * 异步主从（{@code clusterType: async}）是 Percona 的 tech preview，不进表单。
 *
 * <p><b>凭据</b>：Operator 自己生成 {@code <cr>-secrets}（root/xtrabackup 等），平台不写口令 Secret。
 */
@Component
public class MysqlMiddlewareAdapter implements MiddlewareAdapter {

    private static final String GROUP = "ps.percona.com";
    private static final String VERSION = "v1";
    private static final String IMAGE = "percona/percona-server:8.4";

    static final CustomResourceDefinitionContext CRD = new CustomResourceDefinitionContext.Builder()
            .withGroup(GROUP).withVersion(VERSION)
            .withKind("PerconaServerMySQL").withPlural("perconaservermysqls")
            .withScope("Namespaced").build();

    @Override
    public String code() {
        return "mysql";
    }

    @Override
    public boolean usesPlatformPassword() {
        return false;
    }

    @Override
    public List<CrSpec> buildCreate(MiddlewareCreateRequestDTO req) {
        int size = sizeOf(req);
        Map<String, Object> mysql = new LinkedHashMap<>();
        mysql.put("clusterType", "group-replication");
        mysql.put("size", size);
        mysql.put("image", IMAGE);
        mysql.put("resources", resourcesOf(req));
        mysql.put("volumeSpec", volumeSpecOf(req));

        // 可变结构：applyScale 会同步改 haproxy.size
        Map<String, Object> haproxy = new LinkedHashMap<>();
        haproxy.put("enabled", true);
        haproxy.put("size", size);
        Map<String, Object> proxy = new LinkedHashMap<>();
        proxy.put("haproxy", haproxy);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("mysql", mysql);
        // HAProxy 作为统一入口（主库写、副本读）
        spec.put("proxy", proxy);
        return List.of(new CrSpec(CRD, resource(req, "PerconaServerMySQL", spec)));
    }

    @Override
    public List<CustomResourceDefinitionContext> contexts() {
        return List.of(CRD);
    }

    @Override
    public void applyScale(GenericKubernetesResource live, CustomResourceDefinitionContext context,
                           MiddlewareScaleRequestDTO req) {
        Map<String, Object> spec = specOf(live);
        Object mysqlObj = spec.get("mysql");
        if (!(mysqlObj instanceof Map<?, ?>)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "实例规格不可识别");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> mysql = (Map<String, Object>) mysqlObj;
        boolean single = "SINGLE".equals(req.getMode());
        if (req.getReplicas() != null) {
            if (single) {
                if (req.getReplicas() != 1) {
                    throw new CloudPlatformException(EnumResponseType.ERROR,
                            "单节点形态不支持调整节点数；要扩容请删除后按「高可用集群」重建");
                }
            } else {
                int target = Math.max(3, req.getReplicas());
                mysql.put("size", target);
                // HAProxy 入口跟数据节点同步扩，否则入口只有一个副本、没有冗余
                Object proxyObj = spec.get("proxy");
                if (proxyObj instanceof Map<?, ?> proxy
                        && ((Map<String, Object>) proxy).get("haproxy") instanceof Map<?, ?> haproxy) {
                    ((Map<String, Object>) haproxy).put("size", target);
                }
            }
        }
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("requests", nonNullMap("cpu", req.getCpuRequest(), "memory", req.getMemoryRequest()));
        resources.put("limits", nonNullMap("cpu", req.getCpuLimit(), "memory", req.getMemoryLimit()));
        mysql.put("resources", resources);
    }

    @Override
    public String statusSelector(String name) {
        return "app.kubernetes.io/instance=" + name + ",app.kubernetes.io/component=mysql";
    }

    @Override
    public String pvcSelector(String name, String instanceId) {
        // Percona 给数据卷打的标签
        return "app.kubernetes.io/instance=" + name;
    }

    // ==================== 内部 ====================

    private static int sizeOf(MiddlewareCreateRequestDTO req) {
        int replicas = req.getReplicas() == null ? 1 : req.getReplicas();
        return "CLUSTER".equals(req.getMode()) ? Math.max(3, replicas) : 1;
    }

    private static Map<String, Object> volumeSpecOf(MiddlewareCreateRequestDTO req) {
        Map<String, Object> claimSpec = new LinkedHashMap<>();
        claimSpec.put("resources", Map.of("requests", Map.of("storage",
                req.getStorageSize() == null || req.getStorageSize().isBlank() ? "10Gi" : req.getStorageSize())));
        if (req.getStorageClass() != null && !req.getStorageClass().isBlank()) {
            claimSpec.put("storageClassName", req.getStorageClass());
        }
        return Map.of("persistentVolumeClaim", claimSpec);
    }

    private static Map<String, Object> resourcesOf(MiddlewareCreateRequestDTO req) {
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("requests", nonNullMap("cpu", req.getCpuRequest(), "memory", req.getMemoryRequest()));
        resources.put("limits", nonNullMap("cpu", req.getCpuLimit(), "memory", req.getMemoryLimit()));
        return resources;
    }

    private GenericKubernetesResource resource(MiddlewareCreateRequestDTO req, String kind,
                                               Map<String, Object> spec) {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion(GROUP + "/" + VERSION);
        res.setKind(kind);
        ObjectMeta meta = new ObjectMeta();
        meta.setName(req.getName());
        meta.setNamespace(req.getNamespace());
        meta.setLabels(Map.of(
                "platform.coding/managed-by", "middleware",
                "platform.coding/middleware-instance",
                req.getInstanceId() == null ? "-" : req.getInstanceId()));
        res.setMetadata(meta);
        res.setAdditionalProperty("spec", spec);
        return res;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> specOf(GenericKubernetesResource res) {
        Object spec = res.getAdditionalProperties().get("spec");
        return spec instanceof Map ? (Map<String, Object>) spec : new LinkedHashMap<>();
    }

    private static Map<String, Object> nonNullMap(String k1, String v1, String k2, String v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (v1 != null && !v1.isBlank()) {
            m.put(k1, v1);
        }
        if (v2 != null && !v2.isBlank()) {
            m.put(k2, v2);
        }
        return m;
    }
}
