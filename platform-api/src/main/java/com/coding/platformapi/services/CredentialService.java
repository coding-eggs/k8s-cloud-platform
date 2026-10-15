package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.utils.ULIDGenerator;
import com.coding.data.mapper.system.PlatformCredentialMapper;
import com.coding.data.models.system.PlatformCredential;
import com.coding.platformapi.credential.CredentialCipher;
import com.coding.platformapi.credential.CredentialProbeClient;
import com.coding.platformapi.credential.CredentialSecret;
import com.coding.platformapi.credential.ProbeResult;
import com.coding.platformapi.models.CredentialCreateRequest;
import com.coding.platformapi.models.CredentialTestRequest;
import com.coding.platformapi.models.CredentialTestResult;
import com.coding.platformapi.models.CredentialUpdateRequest;
import com.coding.platformapi.models.CredentialView;
import com.coding.platformapi.registry.CredentialRef;
import com.coding.platformapi.registry.ProbeSpec;
import com.coding.platformapi.registry.RegistryDialectRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.util.Date;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 凭据库。设计见 docs/superpowers/specs/2026-10-13-credential-management-design.md。
 *
 * <h3>三条不可动摇的契约</h3>
 * <ol>
 *   <li><b>永不回显</b>：对外只有 {@link CredentialView}，它没有承载凭据内容的字段。
 *       认证字段只以 {@code hasSecret} / {@code fingerprint} 两种派生形态出现。</li>
 *   <li><b>明文只在进程内</b>：只有 {@link #resolveSecret} 返回明文，它是内部消费方
 *       （S3.1 / S3.5）的入口，**不暴露成 HTTP 端点**。</li>
 *   <li><b>测试连接不改变业务状态</b>：它只写 {@code last_verified_*} 两列。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialService {

    /**
     * 合法的连接类型。
     *
     * <p><b>本批只放已实现的方言值</b>：{@code REGISTRY} 在 S3.1 里被拆成具体的方言
     * （Harbor / 通用 v2 / Docker Hub），本批只落 {@code HARBOR}。**有意不预放空值** ——
     * 留一个「能选中但没实现」的类型，管理员会建出一条建了也没用的连接，比不做更糟
     * （与 S0.2 拒掉那个恒为空的 scope 列同一判断）。
     *
     * <p>扩展位由 {@code RegistryDialect} 接口 + {@code RegistryDialectRegistry} + 一条护栏测试提供：
     * 加一档 = 加一个枚举值 + 一个实现类 + 一个 UI 选项，其余零改动。
     *
     * <p>⚠️ <b>{@code DOCKERHUB} 的历史（两次翻转，别再翻回去）</b>：
     * 2026-10-14 它从本集合移除（改成平台的**内置档**：没有行、没有 authType、没有密文），
     * 理由是「Docker Hub 在这平台里的角色就是公共镜像的来源，私有镜像该放自建 Harbor」；
     * <b>2026-10-15 用户撤销该裁定</b> —— 私有 Docker Hub 要能配（用户名 + PAT），于是它回到凭据表。
     * 现在两者**并存**：内置档（匿名）只答公共镜像，凭据行只答"我名下"（见 {@code DockerHubDialect}）。
     */
    private static final Set<String> TYPES = Set.of("GIT", "HARBOR", "DOCKERHUB");

    private static final String AUTH_BASIC = "BASIC";

    /** 匿名：没有凭据，出站请求不带 Authorization 头。 */
    static final String AUTH_NONE = "NONE";

    /**
     * GIT 的认证方式。
     *
     * <p>⚠️ GIT <b>不是</b> {@code RegistryDialect}（它不是镜像仓库），所以没法通过接口声明 ——
     * 这是全平台**唯一**一处「类型的认证方式不在 dialect 里」的例外。
     * 再加类型时先问「它是不是一个镜像仓库」，是的话就去实现 dialect。
     */
    private static final Set<String> GIT_AUTH_TYPES = Set.of(AUTH_BASIC, AUTH_NONE);

    private final PlatformCredentialMapper mapper;

    private final CredentialCipher cipher;

    private final CredentialProbeClient probeClient;

    /** 「该类型允许哪种认证方式」要问它 —— 见 {@link #allowedAuthTypes}。 */
    private final RegistryDialectRegistry dialects;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public List<CredentialView> list() {
        return mapper.selectAll().stream().map(CredentialService::toView).toList();
    }

    public CredentialView create(CredentialCreateRequest req) {
        validateCommon(req.getName(), req.getType(), req.getEndpoint());
        validateAuthOnCreate(req.getType(), req.getAuthType(), req.getUsername(), req.getSecret());
        if (mapper.selectByName(req.getName()) != null) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_NAME_EXIST);
        }

        PlatformCredential c = new PlatformCredential();
        c.setId(ULIDGenerator.generateULID());
        c.setName(req.getName());
        c.setType(req.getType());
        c.setEndpoint(normalizeEndpoint(req.getType(), req.getEndpoint()));
        c.setAuthType(req.getAuthType());
        if (AUTH_NONE.equals(req.getAuthType())) {
            // 匿名：**不写密文**，而不是写一份 username/secret 都是 null 的密文 ——
            // hasSecret 由「secret_encrypted 是否为空」直接得出，不用解密就能判断
            c.setSecretEncrypted(null);
            c.setSecretFingerprint(null);
        } else {
            String json = toJson(new CredentialSecret(req.getUsername(), req.getSecret()));
            c.setSecretEncrypted(cipher.encrypt(json));
            c.setSecretFingerprint(CredentialCipher.fingerprint(json));
        }
        c.setDescription(req.getDescription());
        c.setEnabled(req.getEnabled() == null || req.getEnabled() ? 1 : 0);
        // **默认关**：逐条显式授权（spec §5.3）。不传就是不授权。
        c.setUsableByPipelines(req.getUsableByPipelines() != null && req.getUsableByPipelines() ? 1 : 0);

        mapper.insert(c);
        return toView(c);
    }

    /**
     * 更新。{@code username} / {@code secret} **留空 = 保持不变**（逐字段）——
     * 这是「永不回显」的必然结果：表单里拿不到旧值，「空」只能解读为「不改」。
     *
     * <p>{@code authType} 变更时**必须重填**：密文是绑 authType 解释的 JSON，跨类型沿用语义会对不上。
     */
    public CredentialView update(CredentialUpdateRequest req) {
        PlatformCredential old = mapper.selectById(req.getId());
        if (old == null) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_NOT_EXIST);
        }
        validateCommon(req.getName(), req.getType(), req.getEndpoint());

        PlatformCredential byName = mapper.selectByName(req.getName());
        if (byName != null && !byName.getId().equals(old.getId())) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_NAME_EXIST);
        }

        PlatformCredential c = new PlatformCredential();
        c.setId(old.getId());
        c.setName(req.getName());
        c.setType(req.getType());
        c.setEndpoint(normalizeEndpoint(req.getType(), req.getEndpoint()));
        c.setAuthType(req.getAuthType());
        c.setDescription(req.getDescription());
        c.setEnabled(req.getEnabled() == null || req.getEnabled() ? 1 : 0);
        // ⚠️ 授权开关「缺省 = 保持不变」（与下面的 username/secret 同一条约定）：
        // 把"没传"当成"关掉"会让一次只改描述的保存悄悄撤销授权。
        c.setUsableByPipelines(req.getUsableByPipelines() == null
                ? (old.getUsableByPipelines() == null ? 0 : old.getUsableByPipelines())
                : (req.getUsableByPipelines() ? 1 : 0));

        applyAuth(old, c, req);

        mapper.update(c);
        return toView(c);
    }

    public void delete(String id) {
        if (mapper.selectById(id) == null) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_NOT_EXIST);
        }
        mapper.deleteById(id);
    }

    public CredentialTestResult test(CredentialTestRequest req) {
        PlatformCredential c = mapper.selectById(req.getId());
        if (c == null) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_NOT_EXIST);
        }
        CredentialSecret s = resolveSecret(req.getId());

        ProbeResult result;
        if ("GIT".equals(c.getType())) {
            if (!StringUtils.hasText(req.getProbeUrl())) {
                throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                        "探测 Git 凭据需要给出一个用于测试的仓库地址"
                      + "（凭据是按 host 配的，能不能用取决于具体仓库的权限）");
            }
            result = probeClient.probeGit(req.getProbeUrl(), s.getUsername(), s.getSecret());
        } else {
            // ⚠️ 走分派表而不是自己判 type —— 手写的「哪些档的探测地址不是 <endpoint>/v2/」清单
            // 就是第二个真相源（S3.1 终审删掉 RegistryService.REGISTRY_TYPES 时反对的那个）
            CredentialRef ref = new CredentialRef(c.getId(), c.getName(), c.getType(),
                    c.getEndpoint(), s.getUsername(), s.getSecret());
            ProbeSpec spec = dialects.of(c.getType()).probeSpec(ref);
            result = probeClient.probeRegistry(spec.url(), s.getUsername(), s.getSecret(),
                    spec.notFoundMessage(), spec.authorizationHeader());
        }

        mapper.updateVerifyResult(c.getId(), result.ok(),
                result.ok() ? null : truncate(result.message()), new Date());
        return new CredentialTestResult(result.ok(), result.message(), result.suggestedUrl());
    }

    /**
     * 取明文凭据 —— **内部消费方（S3.1 / S3.5）的唯一入口，不暴露成 HTTP 端点**。
     *
     * <p><b>本方法不记审计</b>：审计由调用方那一层负责。一次 CI 运行可能取用多次，
     * 记在这里会让一次运行产生 N 条记录（spec §11）。
     */
    public CredentialSecret resolveSecret(String id) {
        PlatformCredential c = mapper.selectById(id);
        if (c == null) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_NOT_EXIST);
        }
        // ⚠️ 匿名的行没有密文（auth_type=NONE，两列都是 null）—— 返回两个 null 字段而不是抛。
        // 抛的话「测试连接」对**每一条**匿名连接都报「凭据密文为空」，
        // 而不是那句专门为匿名写的「匿名仍可拉取公开仓库」。
        if (!StringUtils.hasText(c.getSecretEncrypted())) {
            return new CredentialSecret(null, null);
        }
        return fromJson(cipher.decrypt(c.getSecretEncrypted()));
    }

    private void validateCommon(String name, String type, String endpoint) {
        if (!StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID, "名称必填");
        }
        if (!TYPES.contains(type)) {
            // 从 TYPES 动态取 —— 写死的话会像 `GIT / REGISTRY` 那样过时，
            // 而那句文案恰好会把管理员引去用**已经被拒**的那个类型。
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                    "凭据类型仅支持 " + String.join(" / ", TYPES) + "，收到：" + type);
        }
        if (!StringUtils.hasText(endpoint)) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID, "地址必填");
        }
        // authType 的合法性由 requireAllowedAuthType 判（它要知道 type 才问得出允许集合），
        // 这里不再硬编码 BASIC
    }

    /**
     * 地址归一。**问 dialect**（GIT 没有方言，原样 trim）——
     * Docker Hub 那一档会把它统一成 {@code docker.io} 并把别的地址拒掉（见 {@code normalizeEndpoint}）。
     */
    private String normalizeEndpoint(String type, String endpoint) {
        return "GIT".equals(type)
                ? endpoint.trim()
                : dialects.of(type).normalizeEndpoint(endpoint);
    }

    /** 该类型允许的认证方式。**问 dialect，不维护手写清单**（spec §3.7）。 */
    private Set<String> allowedAuthTypes(String type) {
        if ("GIT".equals(type)) {
            return GIT_AUTH_TYPES;
        }
        // validateCommon 已经拒过未知 type，所以走到这里要么是 GIT，要么是已实现的方言
        return dialects.of(type).authTypes();
    }

    private void requireAllowedAuthType(String type, String authType) {
        Set<String> allowed = allowedAuthTypes(type);
        if (authType == null || !allowed.contains(authType)) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                    "该类型支持的认证方式为 " + String.join(" / ", allowed) + "，收到：" + authType);
        }
    }

    private void validateAuthOnCreate(String type, String authType, String username, String secret) {
        requireAllowedAuthType(type, authType);
        if (AUTH_NONE.equals(authType)) {
            if (StringUtils.hasText(username) || StringUtils.hasText(secret)) {
                throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                        "认证方式为匿名时不能填写用户名与密码");
            }
            return;
        }
        if (!StringUtils.hasText(username) || !StringUtils.hasText(secret)) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                    "认证方式为用户名密码时，用户名与密码/令牌都必须填写");
        }
    }

    /**
     * 更新时的认证处理：**先合并出最终的用户名/密码，再校验合并结果**（而不是校验请求里的原始值）。
     *
     * <p>这样「只改了用户名、密码留空沿用旧的」与「从匿名改成 BASIC 必须重填」是同一条判据，
     * 不会各写一套 —— S0.2 原来那条独立的「authType 变更必须重填」规则被它完整吸收
     * （BASIC / NONE 之间只有两种切换，两种都在下面处理了）。
     *
     * <p>⚠️ 改成 {@code NONE} **即清空凭据** —— S0.2 原来的「留空 = 保持不变」是「永不回显」的
     * 必然结果，但它同时意味着**凭据一旦填了就再也清不掉**：表单里拿不到旧值，"空"只能读作"不改"。
     * 这条死路在本批被 {@code NONE} 打开（删了重建不再是唯一出路）。
     */
    private void applyAuth(PlatformCredential old, PlatformCredential target, CredentialUpdateRequest req) {
        requireAllowedAuthType(req.getType(), req.getAuthType());

        if (AUTH_NONE.equals(req.getAuthType())) {
            if (StringUtils.hasText(req.getUsername()) || StringUtils.hasText(req.getSecret())) {
                throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                        "认证方式为匿名时不能填写用户名与密码");
            }
            target.setSecretEncrypted(null);
            target.setSecretFingerprint(null);
            return;
        }

        // 逐字段合并：哪个字段空就沿用旧值（旧值要解密一次才拿得到）
        boolean oldHadBasic = AUTH_BASIC.equals(old.getAuthType())
                && StringUtils.hasText(old.getSecretEncrypted());
        CredentialSecret current = oldHadBasic
                ? fromJson(cipher.decrypt(old.getSecretEncrypted()))
                : new CredentialSecret(null, null);
        String username = StringUtils.hasText(req.getUsername()) ? req.getUsername() : current.getUsername();
        String secret = StringUtils.hasText(req.getSecret()) ? req.getSecret() : current.getSecret();
        if (!StringUtils.hasText(username) || !StringUtils.hasText(secret)) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                    "认证方式为用户名密码时，用户名与密码/令牌都必须填写"
                  + (oldHadBasic ? "" : "（从匿名改过来时必须重填）"));
        }
        // 合并结果与旧值逐字段相同 → **原样沿用旧密文**，不重新加密。
        // AESUtils 每次调用用随机 salt + IV，重加密会让密文字节变化 ——
        // 而 S0.2 的契约是「留空 = 保持不变」：「只改了描述」不该顺带把凭据重写一遍。
        if (oldHadBasic
                && Objects.equals(username, current.getUsername())
                && Objects.equals(secret, current.getSecret())) {
            target.setSecretEncrypted(old.getSecretEncrypted());
            target.setSecretFingerprint(old.getSecretFingerprint());
            return;
        }
        String json = toJson(new CredentialSecret(username, secret));
        target.setSecretEncrypted(cipher.encrypt(json));
        target.setSecretFingerprint(CredentialCipher.fingerprint(json));
    }

    private String toJson(CredentialSecret s) {
        return jsonMapper.writeValueAsString(s);
    }

    private CredentialSecret fromJson(String json) {
        try {
            return jsonMapper.readValue(json, CredentialSecret.class);
        } catch (Exception e) {
            // 解密成功但 JSON 解析失败 = 密文或主密钥不对；不降级、不返回半个对象
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_CIPHERTEXT_INVALID,
                    "凭据内容无法解析（密文损坏或主密钥与写入时不一致）");
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 255 ? s : s.substring(0, 255);
    }

    /** 实体 → 视图。**这里就是「永不回显」的落点**：密文与用户名都不进视图。 */
    private static CredentialView toView(PlatformCredential c) {
        CredentialView v = new CredentialView();
        v.setId(c.getId());
        v.setName(c.getName());
        v.setType(c.getType());
        v.setEndpoint(c.getEndpoint());
        v.setAuthType(c.getAuthType());
        v.setHasSecret(StringUtils.hasText(c.getSecretEncrypted()));
        v.setFingerprint(c.getSecretFingerprint());
        v.setDescription(c.getDescription());
        v.setEnabled(c.getEnabled() != null && c.getEnabled() == 1);
        v.setUsableByPipelines(c.getUsableByPipelines() == null ? 0 : c.getUsableByPipelines());
        v.setLastVerifiedAt(c.getLastVerifiedAt());
        v.setLastVerifyError(c.getLastVerifyError());
        v.setCreatedAt(c.getCreatedAt());
        v.setUpdatedAt(c.getUpdatedAt());
        return v;
    }

    // ---- 流水线里的逐条授权（spec §5.3）----

    /**
     * 可以给流水的 git / 推送节点引用的连接。**窄投影，只回三个字段** ——
     * 与 S3.1 的 {@code /registry/connections} 同一形状：租户进不去凭据页
     * （那个端点要 {@code platform:credential:read}），所以不能复用 {@code /credential/list}。
     */
    public List<ConnectionView> pipelineUsableConnections(String type) {
        return mapper.selectAll().stream()
                .filter(c -> type.equals(c.getType()))
                .filter(c -> c.getUsableByPipelines() != null && c.getUsableByPipelines() == 1)
                .filter(c -> c.getEnabled() != null && c.getEnabled() == 1)
                .sorted(Comparator.comparing(PlatformCredential::getName,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(c -> new ConnectionView(c.getId(), c.getName(), c.getEndpoint()))
                .toList();
    }

    /**
     * 引用一条连接前必须过这里。**明确拒绝，绝不静默降级成"匿名去拉"** ——
     * 静默降级会让租户以为用的是自己的凭据，实际却是平台匿名出站（spec §5.3 的边界）。
     *
     * @param expectedType 这个节点要的是 GIT 还是 registry 连接；类型不符也算未授权。
     *                     **传 null = 不校验类型**（镜像节点只要求「是一条被授权的 registry 连接」，
     *                     它不关心对方是 Harbor 还是别的方言）
     */
    public void assertUsableByPipelines(String id, String expectedType) {
        PlatformCredential c = id == null ? null : mapper.selectById(id);
        if (c == null) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_NOT_EXIST,
                    "连接不存在，未授权在流水线里使用：" + id);
        }
        if (expectedType != null && !expectedType.equals(c.getType())) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                    "连接类型不符（需要 " + expectedType + "，实际 " + c.getType() + "），未授权在流水线里使用");
        }
        if (c.getUsableByPipelines() == null || c.getUsableByPipelines() != 1) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                    "该连接未授权在流水线里使用：" + c.getName());
        }
    }

    /**
     * 可在流水线的**镜像节点**里引用的连接 —— 即除 git 之外的那些（Harbor / Docker Hub…）。
     *
     * <p>与 git 那条同一形状、同一 code：都是「列出可在流水线里使用的连接」，只是面向的节点不同。
     */
    public List<ConnectionView> pipelineUsableRegistryConnections() {
        return mapper.selectAll().stream()
                .filter(c -> !"GIT".equals(c.getType()))
                .filter(c -> c.getUsableByPipelines() != null && c.getUsableByPipelines() == 1)
                .filter(c -> c.getEnabled() != null && c.getEnabled() == 1)
                .sorted(Comparator.comparing(PlatformCredential::getName,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(c -> new ConnectionView(c.getId(), c.getName(), c.getEndpoint()))
                .toList();
    }

    /**
     * 解析一条连接，给「触发一次运行」用：**先校授权、再解密**，一次拿到 endpoint 与凭据。
     *
     * <p>⚠️ 返回值里带明文凭据，调用方只许把它交给 k8s-server 去建 Secret ——
     * 绝不进渲染结果（预览页会把渲染结果原样给用户看），也不进日志。
     */
    public com.coding.common.models.pipeline.PipelineConnectionRef resolveForPipeline(String id,
                                                                                       String expectedType) {
        assertUsableByPipelines(id, expectedType);
        PlatformCredential c = mapper.selectById(id);
        CredentialSecret secret = resolveSecret(id);
        return new com.coding.common.models.pipeline.PipelineConnectionRef(
                c.getId(), c.getEndpoint(), secret.getUsername(), secret.getSecret());
    }

    /** 窄投影：只有身份与地址，**没有认证方式的任何痕迹**。 */
    public record ConnectionView(String id, String name, String endpoint) {
    }
}
