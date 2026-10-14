package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.data.mapper.system.PlatformCredentialMapper;
import com.coding.data.models.system.PlatformCredential;
import com.coding.platformapi.cache.RedisJsonCache;
import com.coding.platformapi.credential.CredentialCipher;
import com.coding.platformapi.credential.CredentialSecret;
import com.coding.platformapi.models.RegistryBrowseRequest;
import com.coding.platformapi.models.RegistryConnectionView;
import com.coding.platformapi.models.RegistrySuggestResponse;
import com.coding.platformapi.registry.BrowseResult;
import com.coding.platformapi.registry.CredentialRef;
import com.coding.platformapi.registry.RegistryDialectRegistry;
import com.coding.platformapi.registry.RegistryTargetResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

/**
 * 镜像仓库的三个只读入口。设计见 docs/superpowers/specs/2026-10-14-registry-design.md。
 *
 * <h3>本类守的两条边界</h3>
 * <ol>
 *   <li><b>出站地址只由凭据的 endpoint 拼</b> —— 用户输入只用于**匹配**连接
 *       （{@link RegistryTargetResolver}），绝不参与构造目标地址。</li>
 *   <li><b>匹配不到就零请求</b> —— 直接返回空，不下沉到方言。</li>
 * </ol>
 *
 * <p>本类**不标审计**：三个入口都是高频只读（补全甚至是每按键一次），逐次写审计等于淹掉审计表，
 * 且它不改任何状态。见 spec §8。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistryService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PlatformCredentialMapper mapper;

    private final CredentialCipher cipher;

    private final RegistryDialectRegistry dialects;

    private final RedisJsonCache cache;

    /** 浏览页的连接下拉：**只四个字段**，只列启用的镜像仓库类连接。 */
    public List<RegistryConnectionView> connections() {
        return enabledRegistryRows().stream()
                .map(c -> new RegistryConnectionView(c.getId(), c.getName(), c.getType(), c.getEndpoint()))
                .toList();
    }

    /** 补全。匹配不到任何连接 → 空列表，**且不发任何请求**。 */
    public RegistrySuggestResponse suggest(String imagePrefix) {
        if (!StringUtils.hasText(imagePrefix)) {
            return new RegistrySuggestResponse(List.of());
        }
        List<PlatformCredential> rows = enabledRegistryRows();
        // ⚠️ 先按 host 匹配 —— **这一步不需要明文**（spec 的硬边界 2：匹配不到就不发请求）。
        // 顺带避掉一个放大效应：以前在**每次 suggest（每按键一次）**里解密**全部**凭据，
        // 任何一条密文解不开（例如主密钥换过）就让**所有人**的补全全挂。
        CredentialRef hit = RegistryTargetResolver.match(imagePrefix,
                rows.stream().map(RegistryService::toPlainRef).toList());
        if (hit == null) {
            return new RegistrySuggestResponse(List.of());
        }
        // 只解密命中的那一条
        PlatformCredential row = rows.stream()
                .filter(r -> r.getId().equals(hit.id()))
                .findFirst()
                .orElse(null);
        if (row == null) {
            return new RegistrySuggestResponse(List.of());
        }
        CredentialRef cred = toRef(row);
        // ⚠️ 只包住**方言调用**，不包住匹配 —— 匹配是纯内存、每次都要按**最新**连接列表算；
        // 把它也塞进缓存会让「刚停用的连接还能补出候选」（spec §5.4）。
        // ⚠️ key 传的是**逻辑键**：RedisJsonCache 自己会加 `platform:cache:` 前缀，
        // 这里再带一次就会变成双前缀 —— 缓存永不命中，而且没人看得出来。
        List<String> values = cache.get(
                "registry:suggest:" + cred.id() + ":" + imagePrefix.trim(),
                Duration.ofMinutes(2),
                new TypeReference<>() { },
                () -> dialects.of(cred.type()).suggest(cred, imagePrefix.trim()));
        // 每条候选带上来源连接名 —— 聚合之后一次输入会问多条连接，这是唯一推不出来的那部分信息
        return new RegistrySuggestResponse(values.stream()
                .map(v -> new RegistrySuggestResponse.Item(v, row.getName()))
                .toList());
    }

    /** 浏览。**不吞错** —— 用户在页面上主动点了一层，失败要说（spec §5.3）。 */
    public BrowseResult browse(RegistryBrowseRequest req) {
        PlatformCredential row = mapper.selectById(req.getCredentialId());
        if (row == null || !isRegistry(row) || !enabled(row)) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_NOT_EXIST,
                    "镜像仓库连接不存在或已停用");
        }
        CredentialRef cred = toRef(row);
        List<String> path = req.getPath() == null ? List.of() : req.getPath();
        return dialects.of(cred.type()).browse(cred, path);
    }

    /** 启用中的镜像仓库类连接。**不解密** —— 明文只在真正要用时（{@link #toRef}）才解。 */
    private List<PlatformCredential> enabledRegistryRows() {
        return mapper.selectAll().stream()
                .filter(c -> isRegistry(c) && enabled(c))
                .toList();
    }

    /** 只带匹配需要的字段（id / name / type / endpoint）的轻量 ref，**不含明文**。 */
    private static CredentialRef toPlainRef(PlatformCredential c) {
        return new CredentialRef(c.getId(), c.getName(), c.getType(), c.getEndpoint(), null, null);
    }

    private CredentialRef toRef(PlatformCredential c) {
        CredentialSecret s = fromJson(cipher.decrypt(c.getSecretEncrypted()));
        return new CredentialRef(c.getId(), c.getName(), c.getType(), c.getEndpoint(),
                s.getUsername(), s.getSecret());
    }

    /**
     * 是不是一个镜像仓库类的连接。
     *
     * <p>⚠️ **问分派表，不自己维护一份 type 清单** —— 手写清单是第二个真相源，
     * 加一档时忘了改它会「全测绿但新档静默失效」。见 {@link RegistryDialectRegistry#supports}。
     */
    private boolean isRegistry(PlatformCredential c) {
        return dialects.supports(c.getType());
    }

    private static boolean enabled(PlatformCredential c) {
        return c.getEnabled() != null && c.getEnabled() == 1;
    }

    private static CredentialSecret fromJson(String json) {
        try {
            return JSON.readValue(json, CredentialSecret.class);
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_CIPHERTEXT_INVALID,
                    "凭据内容无法解析（密文损坏或主密钥与写入时不一致）");
        }
    }
}
