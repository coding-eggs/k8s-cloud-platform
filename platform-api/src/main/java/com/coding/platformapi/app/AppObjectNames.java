package com.coding.platformapi.app;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 应用相关名字的生成规则。
 *
 * <p>K8s 对象名上限 63（DNS-1123 label），而节点 key 最长 20、应用 slug 上限 30 —— 两者相加会超，
 * 所以超长时截断 slug 而不是截断 key（key 是用户在图里认得出的那个名字）。
 */
public final class AppObjectNames {

    public static final int SLUG_MAX = 30;

    public static final int OBJECT_NAME_MAX = 63;

    private AppObjectNames() {
    }

    public static String slug(String appName) {
        String s = appName == null ? "" : appName.trim().toLowerCase(Locale.ROOT);
        s = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        s = s.replaceAll("[^a-z0-9-]+", "-");
        s = s.replaceAll("^-+", "").replaceAll("-+$", "");
        if (s.isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "应用名至少要包含一个字母或数字");
        }
        if (s.length() > SLUG_MAX) {
            s = s.substring(0, SLUG_MAX).replaceAll("-+$", "");
        }
        return s;
    }

    public static String objectName(String slug, String nodeKey) {
        String name = slug + "-" + nodeKey;
        if (name.length() <= OBJECT_NAME_MAX) {
            return name;
        }
        int keep = OBJECT_NAME_MAX - nodeKey.length() - 1;
        String head = slug.substring(0, Math.max(1, keep)).replaceAll("-+$", "");
        return head + "-" + nodeKey;
    }

    public static String envPrefix(String nodeKey) {
        return nodeKey.toUpperCase(Locale.ROOT).replace('-', '_');
    }
}
