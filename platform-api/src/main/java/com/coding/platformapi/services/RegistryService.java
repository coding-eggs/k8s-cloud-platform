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
import tools.jackson.databind.json.JsonMapper;

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

    /** 镜像仓库类的连接类型。**与 {@code CredentialService.TYPES} 的交集由护栏测试保证。** */
    private static final List<String> REGISTRY_TYPES = List.of("HARBOR");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PlatformCredentialMapper mapper;

    private final CredentialCipher cipher;

    private final RegistryDialectRegistry dialects;

    private final RedisJsonCache cache;

    /** 浏览页的连接下拉：**只四个字段**，只列启用的镜像仓库类连接。 */
    public List<RegistryConnectionView> connections() {
        return mapper.selectAll().stream()
                .filter(c -> isRegistry(c) && enabled(c))
                .map(c -> new RegistryConnectionView(c.getId(), c.getName(), c.getType(), c.getEndpoint()))
                .toList();
    }

    /** 补全。匹配不到任何连接 → 空列表，**且不发任何请求**。 */
    public RegistrySuggestResponse suggest(String imagePrefix) {
        if (!StringUtils.hasText(imagePrefix)) {
            return new RegistrySuggestResponse(List.of());
        }
        CredentialRef cred = RegistryTargetResolver.match(imagePrefix, enabledRegistryRefs());
        if (cred == null) {
            return new RegistrySuggestResponse(List.of());
        }
        return new RegistrySuggestResponse(dialects.of(cred.type()).suggest(cred, imagePrefix.trim()));
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

    /**
     * 可参与匹配的连接。**这里已经把凭据解出来了** —— 返回的 {@link CredentialRef} 含明文，
     * 只在本进程内传给方言，绝不外泄。
     */
    private List<CredentialRef> enabledRegistryRefs() {
        return mapper.selectAll().stream()
                .filter(c -> isRegistry(c) && enabled(c))
                .map(this::toRef)
                .toList();
    }

    private CredentialRef toRef(PlatformCredential c) {
        CredentialSecret s = fromJson(cipher.decrypt(c.getSecretEncrypted()));
        return new CredentialRef(c.getId(), c.getName(), c.getType(), c.getEndpoint(),
                s.getUsername(), s.getSecret());
    }

    private static boolean isRegistry(PlatformCredential c) {
        return REGISTRY_TYPES.contains(c.getType());
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
