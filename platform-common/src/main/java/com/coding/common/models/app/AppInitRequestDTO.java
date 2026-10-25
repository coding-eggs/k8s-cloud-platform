package com.coding.common.models.app;

import lombok.Data;

import java.util.Map;

/** platform-api → k8s-server：跑一次中间件初始化（一次性 Job）。 */
@Data
public class AppInitRequestDTO {

    private String clusterId;

    private String namespace;

    /** Job / ConfigMap 名（平台生成，带版本号，便于重试时不撞名） */
    private String jobName;

    /** 中间件 code：mysql / postgresql / redis / elasticsearch / kafka */
    private String code;

    /** 脚本/命令/JSON（进 ConfigMap，不进 Job 清单本身） */
    private String payload;

    private String secretName;

    /** 字面用户名（mysql: root；es: elastic） */
    private String username;

    /** Secret 里用户名的键（postgres / rabbitmq 用） */
    private String usernameKey;

    /** Secret 里口令的键 */
    private String passwordKey;

    /** 普通环境变量（HOST / PORT / BOOTSTRAP / URL） */
    private Map<String, String> env;
}
