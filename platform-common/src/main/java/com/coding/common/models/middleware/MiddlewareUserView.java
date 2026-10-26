package com.coding.common.models.middleware;

import lombok.Data;

/**
 * 中间件实例里的一个账号（列表项）。
 *
 * <p>{@code builtin=true} = 各家的内置账号（MySQL root / PG 应用账号 / RabbitMQ 默认用户 /
 * ES elastic / Milvus root / Kafka、Nacos 无内置账号），**不可删除、只能改口令**。
 */
@Data
public class MiddlewareUserView {

    private String username;

    /** 预设角色（只读 / 读写 / 管理员…），由各适配器声明；没有角色概念的实例为 null */
    private String role;

    private boolean builtin;

    /** 口令在哪个 Secret 的哪个键（不返回明文） */
    private String secretName;

    private String secretKey;
}
