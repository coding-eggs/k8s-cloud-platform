package com.coding.common.models.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 中间件用户管理的公共规则：支持清单 / 用户名 / 口令长度。
 *
 * <p>前端表单、platform-api（归属与口令生成）、k8s-server（执行）三处共用这一份，
 * 各家适配器不再各自发明一套。
 */
public final class MiddlewareUserRules {

    /**
     * 能做用户管理的中间件（Redis 只支持改口令，由调用方按 code 单独判断）。
     *
     * <p><b>nacos 暂不在列</b>：Nacos 3.x 的管理员口令初始化路径、role/permission API 都要按镜像实测才能钉住，
     * 而 chart 1.2.0 也没有管理员口令入口 —— 按 spec §6.8 的降级规则先关掉，等真集群验通过再加回来。
     */
    public static final Set<String> SUPPORTED_CODES = Set.of(
            "mysql", "postgresql", "redis", "rabbitmq", "elasticsearch", "milvus", "kafka");

    public static final int MIN_PASSWORD = 8;
    /** 72 = Milvus common.security.defaultRootPassword 的上限，全平台按它卡 */
    public static final int MAX_PASSWORD = 72;

    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9_]{3,32}$");

    private MiddlewareUserRules() {
    }

    public static boolean validUsername(String username) {
        return username != null && USERNAME.matcher(username).matches();
    }

    public static void requireUsername(String username) {
        if (!validUsername(username)) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "用户名只能用 3~32 位小写字母/数字/下划线");
        }
    }

    public static void requirePasswordLength(String password) {
        if (password == null || password.length() < MIN_PASSWORD || password.length() > MAX_PASSWORD) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "口令长度需在 8~72 位之间");
        }
    }
}
