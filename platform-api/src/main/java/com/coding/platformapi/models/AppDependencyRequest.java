package com.coding.platformapi.models;

import lombok.Data;

import java.util.Map;

/**
 * 依赖接口的入参（add / update / remove / init 共用；update 只用 appId、key、external）。
 *
 * <p>没有任何口令字段：新建的实例口令由中间件模块生成，绑定的实例口令在它自己的 Secret 里，
 * 外部连接只收 Secret 名与键。{@code external} 是 Map 而不是类型化对象，
 * 为的是能把「多出来的字段」原样拿到手、点名拒绝（类型化反序列化会静默丢掉它们）。
 */
@Data
public class AppDependencyRequest {

    private String appId;

    private String tenantId;

    /** 依赖名；与服务名共用一个命名空间 */
    private String key;

    /** MIDDLEWARE / EXTERNAL */
    private String type;

    /** MIDDLEWARE 的来源：CREATE（新建实例）/ BIND（绑定同命名空间已有实例） */
    private String source;

    // ---- CREATE ----

    /** 中间件 code：mysql / redis / kafka ... */
    private String code;

    /** 形态（SINGLE / CLUSTER ...）；缺省取该中间件的默认形态 */
    private String shape;

    private String version;

    private Integer replicas;

    private String cpuRequest;

    private String cpuLimit;

    private String memoryRequest;

    private String memoryLimit;

    private String storageSize;

    private String storageClass;

    // ---- BIND ----

    private String instanceId;

    // ---- EXTERNAL：protocol、host、port、secretName、usernameKey、passwordKey ----

    private Map<String, Object> external;

    // ---- remove ----

    /** 移除 CREATED 依赖时是否同时删除实例；缺省保留（转交中间件模块管理） */
    private Boolean deleteInstance;

    // ---- init ----

    /** 初始化内容（SQL / 命令 / JSON，进 ConfigMap） */
    private String initPayload;

    /** Elasticsearch 初始化的目标路径（以 / 开头），payload 以 PUT 方式提交到它 */
    private String initPath;

    /** 已成功执行过时，再次执行必须带 true */
    private Boolean confirm;
}
