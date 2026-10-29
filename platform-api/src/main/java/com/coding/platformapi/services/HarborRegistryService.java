package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.utils.ULIDGenerator;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.system.PlatformComponentOperationMapper;
import com.coding.data.mapper.system.PlatformCredentialMapper;
import com.coding.data.mapper.system.PlatformRegistryProjectMapper;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.data.models.system.PlatformComponentOperation;
import com.coding.data.models.system.PlatformCredential;
import com.coding.data.models.system.PlatformRegistryProject;
import com.coding.platformapi.credential.CredentialSecret;
import com.coding.platformapi.models.RegistryProjectView;
import com.coding.platformapi.registry.HarborAdminClient;
import com.coding.platformapi.registry.HarborNaming;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Harbor 的租户镜像项目：每租户一个 project + 一个 push/pull robot + 一条租户凭据。
 *
 * <p>装完 Harbor、新建/删除/停用租户时触发；管理员也可手动同步/重建。
 * 幂等：已有可用凭据就跳过，不轮换 robot 口令。
 *
 * <p>管理口令来自集群里的 Secret（{@link SecretService}），只在内存里拼 Basic 头。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HarborRegistryService {

    static final String COMPONENT = "harbor";
    static final String NAMESPACE = "harbor";
    static final String SECRET_NAME = "platform-harbor-secrets";
    static final String ADMIN_USER = "admin";
    static final String SOURCE = "HARBOR";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 同进程内的同步互斥；平台当前单副本部署（跨实例并发不在本期保证） */
    private static final Object LOCK = new Object();

    private final PlatformComponentOperationMapper operations;
    private final PlatformTenantMapper tenants;
    private final PlatformRegistryProjectMapper projects;
    private final PlatformCredentialMapper credentials;
    private final CredentialService credentialService;
    private final com.coding.platformapi.k8s.K8sComponentClient components;
    private final K8sProvisioningService provisioning;
    private final HarborAdminClient harbor;

    public record SyncItem(String tenantId, String tenantName, String project, String status,
                           String message) {
    }

    public record SyncResult(String clusterId, String host, List<SyncItem> items, int failed) {
    }

    /** Harbor 的访问方式：地址（可能带端口）+ 是否 HTTPS */
    public record Access(String host, boolean https) {
        /** 带 scheme 的基址，例如 {@code https://harbor.ackcp.cloud} */
        public String baseUrl() {
            return (https ? "https://" : "http://") + host;
        }
    }

    /** 该集群上次成功安装 Harbor 的访问地址（**不带 scheme**，镜像路径要用它）；没装/无记录 → null */
    public String harborHost(String clusterId) {
        Access access = harborAccess(clusterId);
        return access == null ? null : access.host();
    }

    /**
     * 该集群 Harbor 的访问方式（地址 + scheme）；没装/无记录 → null。
     *
     * <p>⚠️ scheme 不能一律拼 {@code http}：Harbor 换成 route / loadBalancer 暴露后走的是 443
     * （TLS 在网关上终结），旧写法会连到一个早就关掉的 nodePort 上 —— 镜像仓库页与流水线推送
     * 都因此失效（2026-10-29 用户撞到）。以安装快照里的 {@code https} 开关为准。
     */
    public Access harborAccess(String clusterId) {
        PlatformComponentOperation op = operations.selectLatestSuccess(clusterId, COMPONENT);
        if (op == null || op.getValuesSnapshot() == null) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> snapshot = JSON.readValue(op.getValuesSnapshot(), Map.class);
            if (!(snapshot.get("__platform") instanceof Map<?, ?> platform)
                    || !(platform.get("harbor") instanceof Map<?, ?> harbor)) {
                return null;
            }
            // externalHost = 拼好端口的 authority（nodePort/LB 必须带端口）
            Object host = harbor.get("externalHost");
            if (!(host instanceof String s) || s.isBlank()) {
                host = harbor.get("host");
            }
            if (!(host instanceof String authority) || authority.isBlank()) {
                return null;
            }
            return new Access(authority, Boolean.TRUE.equals(harbor.get("https")));
        } catch (Exception e) {
            log.warn("集群 {} 的 Harbor 安装快照无法解析：{}", clusterId, e.getMessage());
            return null;
        }
    }

    /** 平台自己的镜像项目（平台自身构建产物；上游镜像按下面的 {@link #UPSTREAM_PROJECTS} 分） */
    public static final String PLATFORM_PROJECT = "platform";

    /**
     * 上游镜像的项目名 —— **一个上游一个项目**，项目名就是上游主机名。
     *
     * <p>为什么不能都塞进一个 {@code platform} 项目：Harbor 的复制规则只能把镜像放到
     * 「目标项目/源仓库路径」，没法在中间插一段上游主机名。项目按上游分，路径就正好是
     * {@code harbor.ackcp.cloud/quay.io/opstree/redis}，复制规则开箱即用。
     */
    public static final List<String> UPSTREAM_PROJECTS = List.of(
            "docker.io", "quay.io", "ghcr.io", "registry.k8s.io",
            "docker.elastic.co", "registry.istio.io");

    /**
     * 平台在 Harbor 里的**公共项目**（公开读）：自己的 {@code platform} + 各上游主机。
     *
     * <p>镜像仓库页要用它：租户凭据是**项目级** robot，Harbor 的 {@code /projects} 只会回它自己
     * 那一个，而上游镜像全躺在这些公开读的项目里 —— 不补的话页面里看不到它们。
     */
    public static List<String> publicProjectNames() {
        List<String> out = new ArrayList<>();
        out.add(PLATFORM_PROJECT);
        out.addAll(UPSTREAM_PROJECTS);
        return out;
    }

    /**
     * 确保平台自己的项目与各上游项目都存在且**公开读**（匿名可 pull）。
     *
     * <p>这是「镜像来源纳管」的拉取侧前提：公开读之后节点不需要 imagePullSecret；
     * 推送仍走 Harbor 复制规则/管理凭据，不受影响。幂等。
     */
    public void ensurePlatformPublic(String clusterId) {
        Access access = harborAccess(clusterId);
        if (access == null) {
            return;   // 没装 Harbor / 没有成功记录：没得设，交给调用方决定要不要报错
        }
        Basic auth = adminOf(clusterId);
        String baseUrl = access.baseUrl();
        List<String> projects = new ArrayList<>();
        projects.add(PLATFORM_PROJECT);
        projects.addAll(UPSTREAM_PROJECTS);
        for (String project : projects) {
            long projectId = harbor.ensureProject(baseUrl, auth.user(), auth.pass(), project);
            harbor.ensurePublicProject(baseUrl, auth.user(), auth.pass(), projectId, project);
        }
        log.info("集群 {} 的 Harbor 项目 {} 已确保为公开读", clusterId, projects);
    }

    /** 全量同步：平台共享 + 全部启用租户。装完后由组件安装流程调用。 */
    public SyncResult ensureAll(String clusterId) {
        return ensureAll(clusterId, false);
    }

    public SyncResult ensureAll(String clusterId, boolean force) {
        return ensureAll(clusterId, requireAccess(clusterId), force);
    }

    /**
     * @param host  Harbor 访问地址。**安装流程必须显式传**：钩子在写 SUCCESS 之前执行，
     *              这时 {@link #harborHost} 还读不到本集群的安装记录（首次安装会永久自锁）。
     * @param force true = 即使凭据存在也删掉 robot 重建。**首次安装（INSTALL）用 true**：
     *              手工卸载后用同一地址重装时，旧凭据看起来仍然可用，但新 Harbor 里没有那个 robot
     *              —— 不强制重建就会一路 skip，流水线 push 全 401。
     *              升级/编辑重放用 false，避免每次改配置都轮换口令。
     */
    public SyncResult ensureAll(String clusterId, String host, boolean https, boolean force) {
        if (host == null || host.isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "Harbor 访问域名为空：无法同步租户镜像项目");
        }
        return ensureAll(clusterId, new Access(host, https), force);
    }

    private SyncResult ensureAll(String clusterId, Access access, boolean force) {
        synchronized (LOCK) {
            Basic auth = adminOf(clusterId);
            List<SyncItem> items = new ArrayList<>();
            items.add(ensureOne(clusterId, null, HarborNaming.PLATFORM_PROJECT, "platform",
                    access, auth, force));
            for (PlatformTenant t : enabledTenants()) {
                items.add(ensureOne(clusterId, t.getId(), projectOf(t), t.getName(), access, auth, force));
            }
            int failed = (int) items.stream().filter(i -> "ERROR".equals(i.status())).count();
            return new SyncResult(clusterId, access.host(), items, failed);
        }
    }

    /** 某个集群上单租户同步（tenantId 为 null = 平台共享） */
    public SyncItem ensureTenant(String clusterId, String tenantId, boolean force) {
        Access access = requireAccess(clusterId);
        synchronized (LOCK) {
            Basic auth = adminOf(clusterId);
            if (tenantId == null) {
                return ensureOne(clusterId, null, HarborNaming.PLATFORM_PROJECT, "platform",
                        access, auth, force);
            }
            PlatformTenant t = tenants.selectByPrimaryKey(tenantId);
            if (t == null) {
                throw new CloudPlatformException(EnumResponseType.TENANT_NOT_EXIST);
            }
            return ensureOne(clusterId, tenantId, projectOf(t), t.getName(), access, auth, force);
        }
    }

    public SyncItem rebuild(String clusterId, String tenantId) {
        return ensureTenant(clusterId, tenantId, true);
    }

    /** 新建租户时：对每个装过 Harbor 的集群 best-effort 建一次 */
    public void ensureForTenantOnInstalledClusters(String tenantId) {
        PlatformTenant t = tenants.selectByPrimaryKey(tenantId);
        if (t == null) {
            return;
        }
        for (K8sCluster c : provisioning.enabledClusters()) {
            if (harborHost(c.getClusterId()) == null) {
                continue;
            }
            try {
                ensureTenant(c.getClusterId(), tenantId, false);
            } catch (Exception e) {
                log.warn("租户 {} 在集群 {} 的 Harbor 项目同步失败（不阻塞建租户）：{}",
                        tenantId, c.getClusterId(), e.getMessage());
            }
        }
    }

    /** 删除租户：回收 robot 与凭据，**保留项目与镜像** */
    public void onTenantDeleted(String tenantId) {
        for (PlatformRegistryProject row : projects.selectByTenant(tenantId)) {
            try {
                Access access = harborAccess(row.getClusterId());
                if (access != null) {
                    Basic auth = adminOf(row.getClusterId());
                    String baseUrl = access.baseUrl();
                    // 按 project + 名字删，不用台账里的 robot_id：Harbor 重装后 id 会复用，
                    // 拿陈旧 id 去删可能删到别的账号
                    Long projectId = harbor.findProjectId(baseUrl, auth.user(), auth.pass(),
                            row.getProjectName());
                    if (projectId != null) {
                        harbor.deleteRobotByName(baseUrl, auth.user(), auth.pass(), projectId,
                                HarborNaming.robotNameInHarbor(row.getProjectName()));
                    }
                }
            } catch (Exception e) {
                log.warn("删除租户 {} 的 Harbor 机器人失败（保留台账待人工清理）：{}",
                        tenantId, e.getMessage());
            }
            projects.deleteById(row.getId());
        }
        credentials.deleteByTenantAndSource(tenantId, SOURCE);
    }

    /** 停用/启用租户：凭据开关跟随（停用后流水线选不到） */
    public void onTenantStatusChanged(String tenantId, boolean enabled) {
        credentials.updateEnabledByTenantAndSource(tenantId, SOURCE, enabled ? 1 : 0);
    }

    public List<RegistryProjectView> list(String clusterId) {
        return projects.selectByCluster(clusterId).stream().map(row -> {
            RegistryProjectView v = new RegistryProjectView();
            v.setId(row.getId());
            v.setClusterId(row.getClusterId());
            v.setTenantId(row.getTenantId());
            v.setTenantName(row.getTenantId() == null ? "平台共享"
                    : tenantNameOf(row.getTenantId()));
            v.setHost(row.getHost());
            v.setProjectName(row.getProjectName());
            v.setCredentialId(row.getCredentialId());
            v.setStatus(row.getStatus());
            v.setMessage(row.getMessage());
            v.setLastSyncedAt(row.getLastSyncedAt());
            return v;
        }).toList();
    }

    // ==================== 内部 ====================

    private record Basic(String user, String pass) {
    }

    private SyncItem ensureOne(String clusterId, String tenantId, String project, String tenantName,
                               Access access, Basic auth, boolean force) {
        String host = access.host();
        String baseUrl = access.baseUrl();
        try {
            long projectId = harbor.ensureProject(baseUrl, auth.user(), auth.pass(), project);
            PlatformRegistryProject row = projects.selectByClusterAndTenant(clusterId, tenantId);
            // host 变过（Harbor 重装成新地址）也按 force 处理：旧 robot 在新实例里不存在
            boolean hostChanged = row != null && !host.equals(row.getHost());
            // ⚠️ 上一次失败（status=ERROR）的行不能 skip：可能旧 robot 已删、凭据行却还在
            if (!force && !hostChanged && row != null && "READY".equals(row.getStatus())
                    && row.getCredentialId() != null
                    && credentialMatches(row.getCredentialId(), baseUrl)
                    // 凭据行看着正常也可能**权限不够**（robot 的权限建的时候定死）：
                    // 用 robot 自己的凭据真列一次仓库，失败就当"需要重建"
                    && robotUsable(baseUrl, project, row.getCredentialId())) {
                touch(row);
                return new SyncItem(tenantId, tenantName, project, "READY", "已是最新");
            }
            // 旧 robot 先删：Harbor 不返回已存在账号的 secret，只能删后重建。
            // ⚠️ 一律按 project + 名字删，**不用台账里的 robot_id** —— Harbor 重装后 id 会复用，
            // 陈旧 id 可能正好是刚建好的平台/别的租户账号（评审 C/I1）。
            harbor.deleteRobotByName(baseUrl, auth.user(), auth.pass(), projectId,
                    HarborNaming.robotNameInHarbor(project));
            HarborAdminClient.HarborRobot robot =
                    harbor.createRobot(baseUrl, auth.user(), auth.pass(), project);
            // 端点带 scheme：平台装的 Harbor 只有 HTTP（chart 的 tls.enabled=false），
            // 而 HarborDialect 对无 scheme 的端点默认补 https → 浏览镜像仓库时报
            // "Unrecognized SSL message, plaintext connection"（2026-10-27 用户撞到）
            // 建完就用它自己的凭据验一次：权限不够的话问题在这里就暴露，而不是等用户去点
            String probeError = harbor.probeRobot(baseUrl, project, robot.name(), robot.secret());
            String credentialId = credentialService.upsertManagedRegistry(
                    HarborNaming.credentialName(project, clusterId), baseUrl, tenantId,
                    robot.name(), robot.secret(),
                    "Harbor 自动创建：" + clusterNameOf(clusterId) + " / project " + project);
            String status = probeError == null ? "READY" : "ERROR";
            String message = probeError == null ? "已创建（project " + project + "）"
                    : "robot 已创建，但列不出该 project 的仓库：" + probeError;
            save(clusterId, tenantId, host, project, credentialId, robot.id(), status, message);
            return new SyncItem(tenantId, tenantName, project, status,
                    probeError == null ? "已创建" : message);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            save(clusterId, tenantId, host, project, null, null, "ERROR", truncate(message, 1000));
            log.warn("集群 {} / 租户 {} 的 Harbor 项目同步失败：{}", clusterId, tenantId, message);
            return new SyncItem(tenantId, tenantName, project, "ERROR", message);
        }
    }

    /**
     * 凭据行可用**且端点对得上**。
     *
     * <p>端点也要比：早期版本存的是 {@code host:port}（没有 scheme），而 {@code HarborDialect}
     * 对无 scheme 的端点默认补 {@code https://} —— 平台装的 Harbor 只有 HTTP，于是浏览镜像仓库
     * 时报 {@code Unrecognized SSL message, plaintext connection}（2026-10-27 用户撞到）。
     * 对不上就重做一次，把端点修成 {@code http://host:port}。
     */
    private boolean credentialMatches(String credentialId, String endpoint) {
        PlatformCredential c = credentials.selectById(credentialId);
        return c != null && c.getSecretEncrypted() != null && !c.getSecretEncrypted().isBlank()
                && c.getEnabled() != null && c.getEnabled() == 1
                && endpoint.equals(c.getEndpoint());
    }

    /** 用 robot 自己的凭据试一次列仓库；拿不到凭据 / 调用失败都按「需要重建」处理 */
    private boolean robotUsable(String baseUrl, String project, String credentialId) {
        try {
            CredentialSecret s = credentialService.resolveSecret(credentialId);
            if (s == null || s.getSecret() == null) {
                return false;
            }
            return harbor.probeRobot(baseUrl, project, s.getUsername(), s.getSecret()) == null;
        } catch (Exception e) {
            log.warn("校验 Harbor robot 能否列仓库时出错，按需要重建处理：{}", e.getMessage());
            return false;
        }
    }

    private void touch(PlatformRegistryProject row) {
        row.setStatus("READY");
        row.setMessage("已是最新");
        row.setLastSyncedAt(new Date());
        row.setUpdatedAt(new Date());
        projects.updateResult(row);
    }

    private void save(String clusterId, String tenantId, String host, String project,
                      String credentialId, Long robotId, String status, String message) {
        PlatformRegistryProject row = projects.selectByClusterAndTenant(clusterId, tenantId);
        Date now = new Date();
        if (row == null) {
            row = new PlatformRegistryProject();
            row.setId(ULIDGenerator.generateULID());
            row.setClusterId(clusterId);
            row.setTenantId(tenantId);
            row.setCreatedAt(now);
            row.setHost(host);
            row.setProjectName(project);
            row.setCredentialId(credentialId);
            row.setRobotId(robotId);
            row.setStatus(status);
            row.setMessage(message);
            row.setLastSyncedAt(now);
            row.setUpdatedAt(now);
            projects.insert(row);
        } else {
            row.setHost(host);
            row.setProjectName(project);
            if (credentialId != null) {
                row.setCredentialId(credentialId);
            }
            if (robotId != null) {
                row.setRobotId(robotId);
            }
            row.setStatus(status);
            row.setMessage(message);
            row.setLastSyncedAt(now);
            row.setUpdatedAt(now);
            projects.updateResult(row);
        }
    }

    private Access requireAccess(String clusterId) {
        Access access = harborAccess(clusterId);
        if (access == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "该集群没有可用的 Harbor 安装记录（请先安装 Harbor 组件）");
        }
        return access;
    }

    /**
     * Harbor 管理账号的 Basic 头。口令由 k8s-server 生成、只存在集群的 Secret 里，
     * 这里经 PLATFORM 端点读回来（组件命名空间不在租户分配表里，通用 /secrets 读不到）。
     */
    private Basic adminOf(String clusterId) {
        String pass = components.harborAdminPassword(clusterId, NAMESPACE, SECRET_NAME);
        if (pass == null || pass.isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "Harbor 管理口令 Secret 不可用：" + NAMESPACE + "/" + SECRET_NAME);
        }
        return new Basic(ADMIN_USER, pass);
    }

    private static String projectOf(PlatformTenant t) {
        return HarborNaming.projectName(t.getServiceAccount());
    }

    private List<PlatformTenant> enabledTenants() {
        return tenants.listAll().stream()
                .filter(t -> t.getStatus() != null && t.getStatus() == 1)
                .toList();
    }

    private String tenantNameOf(String tenantId) {
        PlatformTenant t = tenants.selectByPrimaryKey(tenantId);
        return t == null ? tenantId : t.getName();
    }

    private String clusterNameOf(String clusterId) {
        for (K8sCluster c : provisioning.enabledClusters()) {
            if (clusterId.equals(c.getClusterId())) {
                return c.getClusterName();
            }
        }
        return clusterId;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
