package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 用户管理的平台侧规则：支持清单 / 用户名 / 口令长度。
 *
 * <p>这三条是前端表单、platform-api、k8s-server 共同口径的唯一定义处，
 * 各家适配器不再各自发明一套。
 */
public final class MiddlewareUserService {

    /** 能做用户管理的中间件（Redis 只支持改口令，另在方法内单独判断） */
    public static final Set<String> SUPPORTED_CODES = Set.of(
            "mysql", "postgresql", "redis", "rabbitmq", "elasticsearch", "milvus", "kafka", "nacos");

    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9_]{3,32}$");

    private MiddlewareUserService() {
    }

    public static boolean validUsername(String username) {
        return username != null && USERNAME.matcher(username).matches();
    }

    /** 口令长度全平台统一按 Milvus 的上限 72 卡；下限 8 位 */
    public static void requirePasswordLength(String password) {
        if (password == null || password.length() < 8 || password.length() > 72) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "口令长度需在 8~72 位之间");
        }
    }
}
