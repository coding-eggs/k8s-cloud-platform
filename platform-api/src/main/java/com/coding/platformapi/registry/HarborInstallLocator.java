package com.coding.platformapi.registry;

import com.coding.data.mapper.system.PlatformComponentOperationMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.data.models.system.PlatformComponentOperation;
import com.coding.platformapi.services.K8sProvisioningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 「Harbor 装在哪些集群、访问地址是什么」的**唯一真相源**。
 *
 * <p>抽出来是因为它有两个消费方，而其中一个会把另一个拖进循环依赖：
 * {@code CredentialService} 要拒掉「地址指向平台 Harbor」的手工新建（spec §6.4.2 规则 2），
 * 而 {@code HarborRegistryService} 依赖 {@code CredentialService}。
 * 放在本类里，两边都只往下指。
 *
 * <p>判据是**组件安装的成功记录**（{@code values_snapshot.__platform.harbor}），不是活探测 ——
 * 这里只回答"平台认为它装在哪"，Harbor 此刻健不健康是组件页的事。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HarborInstallLocator {

    /** 组件 code（与 {@code ClusterComponentCatalog} 一致） */
    static final String COMPONENT = "harbor";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PlatformComponentOperationMapper operations;
    private final K8sProvisioningService provisioning;

    /** 访问方式：地址（可能带端口）+ 是否 HTTPS */
    public record Access(String host, boolean https) {
        /** 带 scheme 的基址，例如 {@code https://harbor.ackcp.cloud} */
        public String baseUrl() {
            return (https ? "https://" : "http://") + host;
        }
    }

    /** 某个集群上装着的 Harbor */
    public record InstalledHarbor(String clusterId, String clusterName, Access access,
                                  String exposeMode) {
    }

    public List<InstalledHarbor> all() {
        List<InstalledHarbor> out = new ArrayList<>();
        for (K8sCluster c : provisioning.enabledClusters()) {
            PlatformComponentOperation op = operations.selectLatestSuccess(c.getClusterId(), COMPONENT);
            if (op == null) {
                continue;
            }
            Map<?, ?> harbor = harborBlock(op.getValuesSnapshot());
            Access access = accessOf(harbor);
            if (access == null) {
                continue;
            }
            out.add(new InstalledHarbor(c.getClusterId(), c.getClusterName(), access,
                    harbor.get("exposeMode") instanceof String s ? s : null));
        }
        return out;
    }

    /** 该集群装了 Harbor 就返回访问方式，否则 null */
    public Access access(String clusterId) {
        PlatformComponentOperation op = operations.selectLatestSuccess(clusterId, COMPONENT);
        return op == null ? null : accessOf(harborBlock(op.getValuesSnapshot()));
    }

    /**
     * 这个地址是不是**平台自己装的那台** Harbor。
     *
     * <p>比较前两边都归一成 {@code host[:port]}（去 scheme、去路径、忽略大小写）——
     * 用户输入的形态不受控，直接比字符串必然漏。
     */
    public boolean isPlatformHost(String endpoint) {
        String h = hostOf(endpoint);
        if (h.isEmpty()) {
            return false;
        }
        return all().stream().anyMatch(i -> h.equalsIgnoreCase(i.access().host()));
    }

    /** endpoint → {@code host[:port]}；容错到「喂什么都返回一个字符串」 */
    public static String hostOf(String endpoint) {
        String e = endpoint == null ? "" : endpoint.trim();
        int scheme = e.indexOf("://");
        if (scheme >= 0) {
            e = e.substring(scheme + 3);
        }
        int slash = e.indexOf('/');
        return (slash >= 0 ? e.substring(0, slash) : e).trim();
    }

    private static Map<?, ?> harborBlock(String snapshot) {
        if (snapshot == null) {
            return null;
        }
        try {
            Map<?, ?> root = JSON.readValue(snapshot, Map.class);
            if (!(root.get("__platform") instanceof Map<?, ?> platform)
                    || !(platform.get("harbor") instanceof Map<?, ?> harbor)) {
                return null;
            }
            return harbor;
        } catch (Exception e) {
            log.warn("Harbor 安装快照无法解析：{}", e.getMessage());
            return null;
        }
    }

    private static Access accessOf(Map<?, ?> harbor) {
        if (harbor == null) {
            return null;
        }
        // externalHost = 拼好端口的 authority（nodePort / loadBalancer 必须带端口）
        Object host = harbor.get("externalHost");
        if (!(host instanceof String s) || s.isBlank()) {
            host = harbor.get("host");
        }
        if (!(host instanceof String authority) || authority.isBlank()) {
            return null;
        }
        return new Access(authority, Boolean.TRUE.equals(harbor.get("https")));
    }
}
