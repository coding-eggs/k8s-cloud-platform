package com.coding.k8sserver.components;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SeaweedFS S3 的 {@code identities} 配置文件（JSON）操作。
 *
 * <p><b>为什么平台要接管</b>：桶级隔离靠的是 {@code Read:bucket} / {@code Write:bucket} 这类
 * **bucket-scoped actions**；chart 自动生成的配置只有 admin + readonly 两个身份，发不了按桶凭据。
 *
 * <p><b>两类身份</b>：
 * <ul>
 *   <li>{@code platform-admin}（{@code ["Admin"]}）：只给平台后端用（建/删桶、ListBuckets）；</li>
 *   <li>每个桶一个身份（{@code Read/Write/List/Tagging:<bucket>}）：发给租户。</li>
 * </ul>
 *
 * <p>⚠️ 静态配置文件在 SeaweedFS 里**优先级最高且完全覆盖 filer 里的动态身份** ——
 * 在 admin UI 的 Users 页手工建的账号不会生效（会被本文件盖掉）。凭据只能由平台发。
 */
public final class SeaweedS3ConfigObjects {

    /** chart 挂载的 Secret key（文件名就是它，挂在 /etc/sw/ 下） */
    public static final String CONFIG_KEY = "seaweedfs_s3_config";
    /** 平台自用的全权身份 */
    public static final String ADMIN_IDENTITY = "platform-admin";
    /** 匿名身份名：SeaweedFS 只认这一个名字，用来接住所有**不带签名**的请求 */
    public static final String ANONYMOUS_IDENTITY = "anonymous";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] AK_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    private SeaweedS3ConfigObjects() {
    }

    /** 只有 platform-admin 一份身份的初始配置 */
    public static String adminOnly(String accessKey, String secretKey) {
        Map<String, Object> admin = identity(ADMIN_IDENTITY, accessKey, secretKey, List.of("Admin"));
        return write(Map.of("identities", List.of(admin)));
    }

    public static Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>(Map.of("identities", new ArrayList<>()));
        }
        Map<String, Object> cfg = JSON.readValue(json, new TypeReference<Map<String, Object>>() {
        });
        if (!(cfg.get("identities") instanceof List<?>)) {
            cfg.put("identities", new ArrayList<>());
        }
        return cfg;
    }

    public static String write(Map<String, Object> config) {
        return JSON.writeValueAsString(config);
    }

    /** 取某个身份的第一组凭据；不存在返回 null */
    @SuppressWarnings("unchecked")
    public static String[] findCredentials(String json, String identityName) {
        for (Map<String, Object> id : identities(parse(json))) {
            if (!identityName.equals(id.get("name"))) {
                continue;
            }
            if (!(id.get("credentials") instanceof List<?> list) || list.isEmpty()) {
                return null;
            }
            Map<String, Object> c = (Map<String, Object>) list.get(0);
            Object ak = c.get("accessKey");
            Object sk = c.get("secretKey");
            return ak == null || sk == null ? null : new String[]{String.valueOf(ak), String.valueOf(sk)};
        }
        return null;
    }

    /** 新增/替换一个身份（同名先删再插，保持文件里身份名唯一） */
    public static String upsertIdentity(String json, String name, String accessKey, String secretKey,
                                        List<String> actions) {
        Map<String, Object> cfg = parse(json);
        List<Map<String, Object>> list = identities(cfg);
        list.removeIf(id -> name.equals(id.get("name")));
        list.add(identity(name, accessKey, secretKey, actions));
        return write(cfg);
    }

    /** 删除一个身份；不存在时原样返回 */
    public static String removeIdentity(String json, String name) {
        Map<String, Object> cfg = parse(json);
        identities(cfg).removeIf(id -> name.equals(id.get("name")));
        return write(cfg);
    }

    /** 身份名（平台约定）：bucket-&lt;桶名&gt;-&lt;8 位随机&gt;，避免同名冲突 */
    public static String identityForBucket(String bucket, String suffix) {
        return "bucket-" + bucket + "-" + suffix;
    }

    /**
     * 给某个桶开/关**匿名读**：只改 {@code anonymous} 这一条身份的 actions，其它身份原样保留。
     *
     * <ul>
     *   <li>匿名身份**没有 credentials** —— 这正是"不带签名也能读"的机制（SeaweedFS 用它接住匿名请求）；</li>
     *   <li>只加 {@code Read:<bucket>}，**不加 List**：知道完整 key 就能下载，但列不出目录；</li>
     *   <li>关闭时把该桶的 {@code Read}/{@code List} 一起摘掉（防止有人手工加过 List 后还留个后门），
     *       actions 空了就把整条身份删掉，保持配置干净。</li>
     * </ul>
     */
    public static String setAnonymousRead(String json, String bucket, boolean enabled) {
        String readAction = "Read:" + bucket;
        String listAction = "List:" + bucket;
        Map<String, Object> cfg = parse(json);
        List<Map<String, Object>> list = identities(cfg);
        Map<String, Object> anon = null;
        for (Map<String, Object> id : list) {
            if (ANONYMOUS_IDENTITY.equals(id.get("name"))) {
                anon = id;
                break;
            }
        }
        List<String> actions = new ArrayList<>();
        if (anon != null && anon.get("actions") instanceof List<?> existing) {
            existing.forEach(a -> actions.add(String.valueOf(a)));
        }
        actions.removeIf(a -> readAction.equals(a) || listAction.equals(a));
        if (enabled) {
            actions.add(readAction);
        }
        if (actions.isEmpty()) {
            list.removeIf(id -> ANONYMOUS_IDENTITY.equals(id.get("name")));
        } else {
            if (anon == null) {
                anon = new LinkedHashMap<>();
                anon.put("name", ANONYMOUS_IDENTITY);
                list.add(anon);
            }
            anon.put("actions", actions);
        }
        return write(cfg);
    }

    /** 当前配置里匿名可读的桶（含手工加的；页面展示用不上，但测试与对账会看） */
    public static List<String> anonymousReadBuckets(String json) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> id : identities(parse(json))) {
            if (!ANONYMOUS_IDENTITY.equals(id.get("name"))) {
                continue;
            }
            if (id.get("actions") instanceof List<?> actions) {
                for (Object a : actions) {
                    String s = String.valueOf(a);
                    if (s.startsWith("Read:")) {
                        out.add(s.substring("Read:".length()));
                    }
                }
            }
        }
        return out;
    }

    /** 20 位大写字母/数字（S3 accessKey 的常见形态） */
    public static String randomAccessKey() {
        char[] out = new char[20];
        for (int i = 0; i < out.length; i++) {
            out[i] = AK_CHARS[RANDOM.nextInt(AK_CHARS.length)];
        }
        return new String(out);
    }

    /** 40 位 base64url（无填充） */
    public static String randomSecretKey() {
        byte[] bytes = new byte[30];
        RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> identities(Map<String, Object> cfg) {
        Object value = cfg.get("identities");
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        List<Map<String, Object>> created = new ArrayList<>();
        cfg.put("identities", created);
        return created;
    }

    private static Map<String, Object> identity(String name, String accessKey, String secretKey,
                                                List<String> actions) {
        Map<String, Object> id = new LinkedHashMap<>();
        id.put("name", name);
        id.put("credentials", List.of(Map.of("accessKey", accessKey, "secretKey", secretKey)));
        id.put("actions", actions);
        return id;
    }
}
