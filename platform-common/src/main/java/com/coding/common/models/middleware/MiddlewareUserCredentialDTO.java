package com.coding.common.models.middleware;

import lombok.Data;

/**
 * 创建用户 / 改口令的**返回**：明文口令只在这里出现一次。
 *
 * <p>前端拿到后弹窗展示（可复制），关闭即丢：不写 store、不进 URL、刷新不保留。
 * 服务端不落库、不记日志、不进审计。
 */
@Data
public class MiddlewareUserCredentialDTO {

    private String username;

    /** 明文口令：仅此一次 */
    private String password;

    private String secretName;

    private String secretKey;
}
