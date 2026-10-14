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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.util.Date;
import java.util.List;
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
     */
    private static final Set<String> TYPES = Set.of("GIT", "HARBOR");

    private static final String AUTH_BASIC = "BASIC";

    private final PlatformCredentialMapper mapper;

    private final CredentialCipher cipher;

    private final CredentialProbeClient probeClient;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public List<CredentialView> list() {
        return mapper.selectAll().stream().map(CredentialService::toView).toList();
    }

    public CredentialView create(CredentialCreateRequest req) {
        validateCommon(req.getName(), req.getType(), req.getEndpoint(), req.getAuthType());
        if (!StringUtils.hasText(req.getUsername()) || !StringUtils.hasText(req.getSecret())) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                    "认证方式为 BASIC 时，用户名与密码/令牌都必须填写");
        }
        if (mapper.selectByName(req.getName()) != null) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_NAME_EXIST);
        }

        String json = toJson(new CredentialSecret(req.getUsername(), req.getSecret()));
        PlatformCredential c = new PlatformCredential();
        c.setId(ULIDGenerator.generateULID());
        c.setName(req.getName());
        c.setType(req.getType());
        c.setEndpoint(req.getEndpoint().trim());
        c.setAuthType(AUTH_BASIC);
        c.setSecretEncrypted(cipher.encrypt(json));
        c.setSecretFingerprint(CredentialCipher.fingerprint(json));
        c.setDescription(req.getDescription());
        c.setEnabled(req.getEnabled() == null || req.getEnabled() ? 1 : 0);

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
        validateCommon(req.getName(), req.getType(), req.getEndpoint(), req.getAuthType());

        PlatformCredential byName = mapper.selectByName(req.getName());
        if (byName != null && !byName.getId().equals(old.getId())) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_NAME_EXIST);
        }

        boolean authTypeChanged = !old.getAuthType().equals(req.getAuthType());
        boolean newSecretGiven = StringUtils.hasText(req.getSecret());
        if (authTypeChanged && !newSecretGiven) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                    "认证方式变更后必须重新填写凭据");
        }

        PlatformCredential c = new PlatformCredential();
        c.setId(old.getId());
        c.setName(req.getName());
        c.setType(req.getType());
        c.setEndpoint(req.getEndpoint().trim());
        c.setAuthType(AUTH_BASIC);
        c.setDescription(req.getDescription());
        c.setEnabled(req.getEnabled() == null || req.getEnabled() ? 1 : 0);

        if (newSecretGiven || StringUtils.hasText(req.getUsername())) {
            // 逐字段合并：哪个字段空就沿用旧值（旧值要解密一次才拿得到）
            CredentialSecret current = fromJson(cipher.decrypt(old.getSecretEncrypted()));
            String username = StringUtils.hasText(req.getUsername()) ? req.getUsername() : current.getUsername();
            String secret = newSecretGiven ? req.getSecret() : current.getSecret();
            String json = toJson(new CredentialSecret(username, secret));
            c.setSecretEncrypted(cipher.encrypt(json));
            c.setSecretFingerprint(CredentialCipher.fingerprint(json));
        } else {
            c.setSecretEncrypted(old.getSecretEncrypted());
            c.setSecretFingerprint(old.getSecretFingerprint());
        }

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
            result = probeClient.probeRegistry(c.getEndpoint(), s.getUsername(), s.getSecret());
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
        return fromJson(cipher.decrypt(c.getSecretEncrypted()));
    }

    private void validateCommon(String name, String type, String endpoint, String authType) {
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
        if (!AUTH_BASIC.equals(authType)) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                    "v1 仅支持 BASIC（HTTPS 用户名 + 密码/令牌）认证方式，收到：" + authType);
        }
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
        v.setLastVerifiedAt(c.getLastVerifiedAt());
        v.setLastVerifyError(c.getLastVerifyError());
        v.setCreatedAt(c.getCreatedAt());
        v.setUpdatedAt(c.getUpdatedAt());
        return v;
    }
}
