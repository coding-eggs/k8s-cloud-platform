package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.utils.AESUtils;
import com.coding.data.mapper.alert.NotificationChannelMapper;
import com.coding.data.mapper.alert.RuleNotifyMapper;
import com.coding.data.models.alert.NotificationChannel;
import com.coding.platformapi.models.NotificationChannelRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
public class NotificationChannelService {

    /**
     * K8s label 值语法；code 会被写进规则的 {@code labels.platform_notify}，
     * 不合法的话规则保存到集群时会直接被 apiserver 拒。
     */
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9]([-A-Za-z0-9_.]*[A-Za-z0-9])?");
    private static final List<String> SUPPORTED_TYPES = List.of("EMAIL");

    private final NotificationChannelMapper mapper;
    private final RuleNotifyMapper bindings;
    private final String aesKey;
    private final JsonMapper json = JsonMapper.builder().build();

    public NotificationChannelService(NotificationChannelMapper mapper,
                                      RuleNotifyMapper bindings,
                                      @Value("${platform.credential.aes-key}") String aesKey) {
        this.mapper = mapper;
        this.bindings = bindings;
        this.aesKey = aesKey;
    }

    public List<NotificationChannel> list() {
        return mapper.selectAll().stream().map(this::masked).toList();
    }

    public List<NotificationChannel> options(String tenantId) {
        return mapper.selectAvailable(tenantId).stream().map(this::masked).toList();
    }

    public NotificationChannel create(NotificationChannelRequest req) {
        validate(req);
        if (mapper.selectByCode(req.getCode()) != null) {
            throw new CloudPlatformException(EnumResponseType.NOTIFY_CHANNEL_INVALID, "code 已存在: " + req.getCode());
        }
        if (Boolean.TRUE.equals(req.getIsDefault())) {
            mapper.clearDefault(req.getTenantId());
        }
        NotificationChannel c = toEntity(req, UUID.randomUUID().toString().replace("-", ""), null);
        Date now = new Date();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        mapper.insert(c);
        return masked(c);
    }

    public NotificationChannel update(String code, NotificationChannelRequest req) {
        NotificationChannel existing = mapper.selectByCode(code);
        if (existing == null) {
            throw new CloudPlatformException(EnumResponseType.NOTIFY_CHANNEL_INVALID, "通知方式不存在: " + code);
        }
        req.setCode(code);                       // code 不可改（它已经写进了规则 label）
        validate(req);
        if (Boolean.TRUE.equals(req.getIsDefault())) {
            mapper.clearDefault(req.getTenantId());
        }
        // 密码留空 = 不修改：沿用库里已存的密文，否则改个名字就把 SMTP 密码抹掉了
        NotificationChannel c = toEntity(req, existing.getId(), existing.getConfig());
        c.setUpdatedAt(new Date());
        mapper.updateByCode(c);
        return masked(c);
    }

    public void delete(String code) {
        int used = bindings.countByChannel(code);
        if (used > 0) {
            throw new CloudPlatformException(EnumResponseType.NOTIFY_CHANNEL_IN_USE,
                    "该通知方式仍被 " + used + " 条规则使用，不能删除");
        }
        mapper.deleteByCode(code);
    }

    /** 发送时按 code 取渠道（不解密）。 */
    public NotificationChannel resolve(String code) {
        return StringUtils.hasText(code) ? mapper.selectByCode(code) : null;
    }

    /** 未绑定时的默认渠道：租户默认 → 全局默认。 */
    public NotificationChannel defaultFor(String tenantId) {
        if (StringUtils.hasText(tenantId)) {
            NotificationChannel c = mapper.selectTenantDefault(tenantId);
            if (c != null) return c;
        }
        return mapper.selectGlobalDefault();
    }

    /** 发送用：把 passwordEnc 解成 password，去掉 passwordEnc。 */
    public Map<String, String> decryptedConfig(NotificationChannel c) {
        Map<String, String> cfg = parse(c.getConfig());
        String enc = cfg.remove("passwordEnc");
        if (StringUtils.hasText(enc)) {
            cfg.put("password", AESUtils.decrypt(enc, aesKey));
        }
        return cfg;
    }

    // ---------- 内部 ----------

    private void validate(NotificationChannelRequest req) {
        if (!StringUtils.hasText(req.getCode()) || req.getCode().length() > 63
                || !CODE.matcher(req.getCode()).matches()) {
            throw new CloudPlatformException(EnumResponseType.NOTIFY_CHANNEL_INVALID,
                    "code 必须是 K8s label 合法值（字母数字 - _ .，≤63），如 ops-mail");
        }
        if (!StringUtils.hasText(req.getName())) {
            throw new CloudPlatformException(EnumResponseType.NOTIFY_CHANNEL_INVALID, "名称必填");
        }
        if (!SUPPORTED_TYPES.contains(req.getType())) {
            throw new CloudPlatformException(EnumResponseType.NOTIFY_CHANNEL_INVALID,
                    "暂不支持的通知类型: " + req.getType());
        }
        Map<String, String> cfg = req.getConfig() == null ? Map.of() : req.getConfig();
        for (String key : List.of("host", "port", "from")) {
            if (!StringUtils.hasText(cfg.get(key))) {
                throw new CloudPlatformException(EnumResponseType.NOTIFY_CHANNEL_INVALID,
                        "EMAIL 配置缺少 " + key);
            }
        }
    }

    /**
     * @param existingConfig 修改时传库里已存的 config（含 passwordEnc），
     *                       用于在「密码留空」时沿用旧密文；新建时传 null
     */
    private NotificationChannel toEntity(NotificationChannelRequest req, String id, String existingConfig) {
        Map<String, String> cfg = new LinkedHashMap<>(req.getConfig() == null ? Map.of() : req.getConfig());
        String password = cfg.remove("password");
        if (StringUtils.hasText(password)) {
            cfg.put("passwordEnc", AESUtils.encrypt(password, aesKey));
        } else if (StringUtils.hasText(existingConfig)) {
            String previous = parse(existingConfig).get("passwordEnc");
            if (StringUtils.hasText(previous)) {
                cfg.put("passwordEnc", previous);
            }
        }
        NotificationChannel c = new NotificationChannel();
        c.setId(id);
        c.setCode(req.getCode());
        c.setName(req.getName());
        c.setType(req.getType());
        c.setConfig(json.writeValueAsString(cfg));
        c.setTenantId(req.getTenantId());
        c.setIsDefault(Boolean.TRUE.equals(req.getIsDefault()));
        c.setEnabled(req.getEnabled() == null || req.getEnabled());
        return c;
    }

    /**
     * 列表/详情不回密码：返回**副本**并摘掉 {@code passwordEnc}。
     * <p>刻意不改原对象 —— 同一个实例既会交给 mapper 落库、也可能被上层继续用，
     * 就地改写会让「已加密的那份」被悄悄抹成脱敏值（测试里表现为取不到明文密码）。
     */
    private NotificationChannel masked(NotificationChannel c) {
        NotificationChannel copy = new NotificationChannel();
        copy.setId(c.getId());
        copy.setCode(c.getCode());
        copy.setName(c.getName());
        copy.setType(c.getType());
        copy.setConfig(maskedConfig(c.getConfig()));
        copy.setTenantId(c.getTenantId());
        copy.setIsDefault(c.getIsDefault());
        copy.setEnabled(c.getEnabled());
        copy.setCreatedAt(c.getCreatedAt());
        copy.setUpdatedAt(c.getUpdatedAt());
        return copy;
    }

    private String maskedConfig(String config) {
        Map<String, String> cfg = parse(config);
        if (!cfg.containsKey("passwordEnc")) return config;
        cfg.remove("passwordEnc");
        return json.writeValueAsString(cfg);
    }

    private Map<String, String> parse(String s) {
        if (!StringUtils.hasText(s)) return new LinkedHashMap<>();
        return json.readValue(s, new TypeReference<LinkedHashMap<String, String>>() {});
    }
}
