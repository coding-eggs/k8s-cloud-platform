package com.coding.platformapi.services;

import com.coding.common.models.pipeline.PipelineConnectionRef;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.system.PlatformCredentialMapper;
import com.coding.data.mapper.system.PlatformRegistryProjectMapper;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.data.models.system.PlatformCredential;
import com.coding.data.models.system.PlatformRegistryProject;
import com.coding.platformapi.credential.CredentialSecret;
import com.coding.platformapi.k8s.K8sLifecycleClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 命名空间的**默认拉取凭据**：把租户的 registry 凭据拼成 docker config，交给 k8s-server 写成 Secret
 * 并挂到该命名空间的 {@code default} ServiceAccount 上。
 *
 * <p>设计见 spec {@code 2026-10-31-platform-tenant-boundary-tightening-design.md} §6。
 *
 * <h2>为什么是 default SA 而不是租户 SA</h2>
 * 租户 SA {@code tn-<sa>} 在 platform-system，Pod 的 {@code serviceAccountName} 只能引用同一个
 * 命名空间里的 SA；就算在租户 ns 再造一份，容器里就会挂上**租户的完整 RBAC token** ——
 * 应用被攻破就是租户被接管。{@code default} 在租户 ns 里没有任何 RoleBinding，正好是
 * 「能拉镜像、摸不到 API」那一档。
 *
 * <h2>合并规则</h2>
 * 一个命名空间里所有租户凭据合并成**一个** Secret（docker config 的 {@code auths} 本来就是按 host
 * 索引的 map）。同 host 冲突时**平台 robot 优先**，其余跳过 —— containerd 对同一 host 只取第一条匹配，
 * 与其让它运行时随机赢，不如在写入时定死。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistryPullSecretService {

    /**
     * 命名空间里那个拉取凭据的固定名字。
     *
     * <p>⚠️ 平台**独占**这个名字：只 createOrReplace 它，绝不碰用户自己建的 Secret；
     * 同名冲突时以平台内容为准（这是"平台托管"的应有之义，写进文档）。
     */
    static final String SECRET_NAME = "platform-registry-pull";

    /** 平台建的租户 Harbor robot 那条台账的终态 —— 只有它代表"凭据确实能用" */
    private static final String READY = "READY";

    private final PlatformRegistryProjectMapper projects;
    private final PlatformCredentialMapper credentials;
    private final CredentialService credentialService;
    private final PlatformTenantNamespaceMapper allocations;
    private final K8sLifecycleClient k8s;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    /** 全量重下发的结果：成功几个、失败几个、失败明细（明细会回给管理员看） */
    public record ResyncResult(int applied, int failed, List<String> failures) {
    }

    /**
     * 该租户在该集群的 docker config json；**一条能用的凭据都没有时返回 null**（调用方据此跳过）。
     *
     * <p>顺序即优先级：先放平台 robot，再放租户其余启用的 registry 凭据；同 host 后到的跳过。
     *
     * <p>⚠️ 产物是**明文凭据**，只允许在内存里传给 k8s-server —— 不落日志、不进响应体。
     */
    public String dockerConfigJson(String clusterId, String tenantId) {
        Map<String, Object> auths = new LinkedHashMap<>();

        PlatformRegistryProject project = projects.selectByClusterAndTenant(clusterId, tenantId);
        if (project != null && READY.equalsIgnoreCase(project.getStatus())
                && StringUtils.hasText(project.getCredentialId())) {
            putAuth(auths, project.getCredentialId());
        }
        for (PlatformCredential c : credentials.listEnabledRegistryByTenant(tenantId)) {
            if (project != null && c.getId().equals(project.getCredentialId())) {
                continue;   // 平台 robot 已放过（可能不是 READY 状态，那就一个都不放）
            }
            putAuth(auths, c.getId());
        }

        if (auths.isEmpty()) {
            return null;
        }
        return jsonMapper.writeValueAsString(Map.of("auths", auths));
    }

    /** 把一个凭据放进 auths（同 host 已存在则**不进**）；匿名凭据（无用户名/口令）也跳过 */
    private void putAuth(Map<String, Object> auths, String credentialId) {
        PlatformCredential c = credentials.selectById(credentialId);
        if (c == null || !StringUtils.hasText(c.getEndpoint())) {
            return;
        }
        String host = PipelineConnectionRef.hostOf(c.getEndpoint());
        if (!StringUtils.hasText(host) || auths.containsKey(host)) {
            return;
        }
        CredentialSecret s = credentialService.resolveSecret(credentialId);
        if (s == null || !StringUtils.hasText(s.getUsername()) || !StringUtils.hasText(s.getSecret())) {
            return;
        }
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("username", s.getUsername());
        entry.put("password", s.getSecret());
        entry.put("auth", Base64.getEncoder().encodeToString(
                (s.getUsername() + ":" + s.getSecret()).getBytes(StandardCharsets.UTF_8)));
        auths.put(host, entry);
    }

    /**
     * 给**一个**命名空间下发（失败只告警）。
     *
     * <p>给"分配命名空间""新建命名空间"这类**不该被它阻塞**的路径用。
     */
    public void ensureQuietly(String clusterId, String tenantId, String namespace) {
        try {
            String json = dockerConfigJson(clusterId, tenantId);
            if (json == null) {
                return;   // 这个集群没配 Harbor / 凭据还没建好 —— 没有可下发的东西
            }
            k8s.ensureRegistryPullSecret(clusterId, namespace, SECRET_NAME, json);
        } catch (Exception e) {
            log.warn("集群 {} 命名空间 {} 的拉取凭据下发失败（不阻塞主流程）：{}",
                    clusterId, namespace, e.getMessage());
        }
    }

    /**
     * 该租户在**这个集群**的全部已分配命名空间重下发（robot 轮换后必须做）。
     *
     * <p>⚠️ 不重下发的后果：老口令留在 Secret 里，拉取 401 —— 现象和"没配凭据"一模一样，很难查。
     *
     * @return 成功下发的命名空间数
     */
    public int resyncTenant(String clusterId, String tenantId) {
        String json = dockerConfigJson(clusterId, tenantId);
        if (json == null) {
            return 0;
        }
        int ok = 0;
        for (PlatformTenantNamespace row : allocations.listByTenantAndCluster(tenantId, clusterId)) {
            try {
                k8s.ensureRegistryPullSecret(clusterId, row.getNamespace(), SECRET_NAME, json);
                ok++;
            } catch (Exception e) {
                log.warn("集群 {} 命名空间 {} 的拉取凭据重下发失败：{}",
                        clusterId, row.getNamespace(), e.getMessage());
            }
        }
        return ok;
    }

    /**
     * 该租户在**所有**集群的已分配命名空间重下发（租户重新启用时补一次）。
     *
     * <p>为什么启用也要补：租户停用期间装的 Harbor / 建的凭据，不会有任何东西去铺设它的命名空间 ——
     * 不补的话，重新启用后第一次拉镜像就是 {@code ImagePullBackOff}。
     *
     * @return 成功下发的命名空间数
     */
    public int resyncTenantEverywhere(String tenantId) {
        Map<String, List<String>> byCluster = new LinkedHashMap<>();
        for (PlatformTenantNamespace row : allocations.listByTenant(tenantId)) {
            byCluster.computeIfAbsent(row.getClusterId(), k -> new ArrayList<>()).add(row.getNamespace());
        }

        int ok = 0;
        for (Map.Entry<String, List<String>> e : byCluster.entrySet()) {
            String clusterId = e.getKey();
            String json;
            try {
                json = dockerConfigJson(clusterId, tenantId);
            } catch (Exception ex) {
                log.warn("租户 {} 在集群 {} 的凭据读取失败（跳过该集群）：{}", tenantId, clusterId, ex.getMessage());
                continue;
            }
            if (json == null) {
                continue;
            }
            for (String ns : e.getValue()) {
                try {
                    k8s.ensureRegistryPullSecret(clusterId, ns, SECRET_NAME, json);
                    ok++;
                } catch (Exception ex) {
                    log.warn("集群 {} 命名空间 {} 的拉取凭据重下发失败：{}", clusterId, ns, ex.getMessage());
                }
            }
        }
        return ok;
    }

    /**
     * 某集群全量重下发（管理员手动入口）。
     *
     * <p>docker config 按租户算一次、复用到它的每个命名空间 —— 不是每个 ns 都去查一遍凭据。
     */
    public ResyncResult resyncCluster(String clusterId) {
        Map<String, List<String>> byTenant = new LinkedHashMap<>();
        for (PlatformTenantNamespace row : allocations.listByCluster(clusterId)) {
            byTenant.computeIfAbsent(row.getTenantId(), k -> new ArrayList<>()).add(row.getNamespace());
        }

        int applied = 0;
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : byTenant.entrySet()) {
            String json;
            try {
                json = dockerConfigJson(clusterId, e.getKey());
            } catch (Exception ex) {
                // 单个租户的凭据读不出来不该拖垮整轮
                failures.add(e.getKey() + "：凭据读取失败 —— " + ex.getMessage());
                continue;
            }
            if (json == null) {
                continue;
            }
            for (String ns : e.getValue()) {
                try {
                    k8s.ensureRegistryPullSecret(clusterId, ns, SECRET_NAME, json);
                    applied++;
                } catch (Exception ex) {
                    failures.add(e.getKey() + "/" + ns + "：" + ex.getMessage());
                }
            }
        }
        log.info("集群 {} 的拉取凭据重下发：成功 {}，失败 {}", clusterId, applied, failures.size());
        return new ResyncResult(applied, failures.size(), List.copyOf(failures));
    }
}
