package com.coding.platformapi.services;

import com.coding.common.models.middleware.MiddlewareUserRules;

import java.util.Set;

/**
 * 用户管理的平台侧规则入口。
 *
 * <p>规则的唯一定义在 platform-common 的 {@link MiddlewareUserRules}（k8s-server 也要用同一份）；
 * 这里保留同名成员给 platform-api 内部与测试引用，不再重复实现。
 */
public final class MiddlewareUserService {

    /** 能做用户管理的中间件（Redis 只支持改口令，另在服务方法内判断） */
    public static final Set<String> SUPPORTED_CODES = MiddlewareUserRules.SUPPORTED_CODES;

    private MiddlewareUserService() {
    }

    public static boolean validUsername(String username) {
        return MiddlewareUserRules.validUsername(username);
    }

    public static void requirePasswordLength(String password) {
        MiddlewareUserRules.requirePasswordLength(password);
    }
}
