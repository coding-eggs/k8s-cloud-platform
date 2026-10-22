package com.coding.common.exception;

import lombok.Getter;

@Getter
public enum EnumResponseType {

    SUCCESS(200,"成功"),
    USER_UN_LOGIN(4003,"未登录"),
    USER_SESSION_EXPIRED(4004, "session 过期"),
    USER_SESSION_EXPIRED_CON(4005,"并发导致 session 过期"),
    BAD_CERTIFICATE(4006,"用户名或密码不正确"),

    NON_AUTH_ENTRY_POINT(403,"权限不足"),
    NON_RESOURCE(404, "资源不存在"),
    NON_KUBE_CONFIG(4004, "kube config 不存在"),
    CLUSTER_DISABLED(5001, "集群已被禁用"),

    INVALID_FORMAT_FAIL(1003,"json 解析失败: "),
    BEAN_VALIDATION_EXCEPTION(1004,"参数校验异常"),
    METHOD_ARGUMENT_NOT_VALID_EXCEPTION(1005,"实体类参数校验异常"),

    SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE(10001, "查询的资源需要资源名称和命名空间"),

    RESOURCE_NOT_EXIST(10002, "资源不存在"),
    RESOURCE_EXIST(10003, "资源已存在"),
    TENANT_NOT_EXIST(10004, "租户不存在"),
    SERVICE_ACCOUNT_NOT_EXIST(10005, "service account 不存在"),

    TENANT_MISMATCH(10006, "租户ID不匹配"),
    TOKEN_TENANT_MISSING(10007, "Token中未找到租户信息"),
    NAMESPACE_NOT_ACCESSIBLE(10008, "无权访问该命名空间"),

    CLUSTER_NOT_EXIST(10009, "集群不存在"),
    TENANT_DISABLED(10010, "租户已被禁用"),
    SERVICE_ACCOUNT_NAME_EXIST(10011, "租户标识(serviceAccount)已被占用"),
    NAMESPACE_ALREADY_ALLOCATED(10012, "该命名空间已分配给此租户"),
    ALLOCATION_NOT_EXIST(10013, "命名空间分配不存在"),
    RBAC_TEMPLATE_NOT_EXIST(10014, "RBAC 模板不存在"),
    RBAC_TEMPLATE_BUILTIN_PROTECTED(10015, "内置模板不可修改或删除"),
    RBAC_TEMPLATE_IN_USE(10016, "模板正被命名空间分配引用，无法删除"),
    K8S_CONNECT_FAILED(10017, "集群连接失败: "),
    INVALID_K8S_NAME(10018, "不符合 K8s 命名规范: "),
    OPERATION_NOT_SUPPORTED(10019, "该资源不支持此操作"),
    HPA_VERSION_UNSUPPORTED(10020, "该集群不支持 HPA（autoscaling）资源"),
    HPA_V1_ONLY_CPU(10021, "该集群 HPA 为 autoscaling/v1，仅支持 CPU 利用率指标（不支持内存/其他指标或 behavior）"),
    HPA_TARGET_ALREADY_BOUND(10022, "该工作负载已绑定 HPA，一个工作负载只能绑定一个 HPA"),

    ROLE_BUILTIN_READONLY(10023, "内置角色不可修改或删除"),
    ROLE_SCOPE_MISMATCH(10024, "角色族与权限点族不一致"),
    TENANT_ADMIN_REQUIRED(10025, "租户至少需保留一名管理员"),
    TENANT_MEMBER_NOT_FOUND(10026, "用户不是该租户成员"),
    PERMISSION_NOT_FOUND(10027, "权限点不存在"),
    QUOTA_NAME_NOT_DEFAULT(10028, "命名空间内平台管理的配额/限制范围对象名必须为 default（平台单份约定）"),
    LIMIT_RANGE_TYPE_UNSUPPORTED(10029, "限制范围类型仅支持 Container / PersistentVolumeClaim"),
    LIMIT_RANGE_VALUE_INVALID(10030, "限制范围取值非法：max 不得小于 min，defaultRequest 不得大于 default，maxLimitRequestRatio 不得小于 1"),
    PERMISSION_ENDPOINT_UNCOVERED(10031, "操作会使端点失去权限覆盖"),
    CREDENTIAL_KEY_MISSING(10032, "凭据加密主密钥未配置"),
    CREDENTIAL_CIPHERTEXT_INVALID(10033, "凭据密文格式无法识别"),
    CREDENTIAL_NOT_EXIST(10034, "凭据不存在"),
    CREDENTIAL_NAME_EXIST(10035, "凭据名称已存在"),
    CREDENTIAL_INVALID(10036, "凭据参数非法"),
    REGISTRY_DIALECT_UNSUPPORTED(10037, "该镜像仓库类型尚未实现"),
    PIPELINE_CANVAS_INVALID(10038, "流水线画布非法"),
    PIPELINE_NODE_UNSUPPORTED(10039, "不支持的流水线节点类型"),
    PIPELINE_INVALID(10040, "流水线参数非法"),

    PVC_RESIZE_INVALID(10042, "PVC 扩容参数非法"),
    K8S_OPERATION_REJECTED(10041, "集群拒绝了该操作: "),
    /**
     * 集群侧 403 —— 与 {@link #K8S_OPERATION_REJECTED} 分开的理由：那个是「对象本身被拒」（校验、终态、
     * 并发冲突），这个是「凭据没这个权限」，处置方式完全不同（前者改请求，后者改 RBAC 模板）。
     * <p>租户域请求拿到它，几乎总是 {@code platform_rbac_template.rules} 少了那个 apiGroup
     * （2026-10-17：CRD 族首见于此）；msg 尾部会拼上 apiserver 原文，apiGroup/resource/verb/SA 都在里面。
     */
    K8S_RBAC_FORBIDDEN(10043, "集群侧 RBAC 拒绝了该操作。若这是租户操作，请检查「RBAC 模板」是否覆盖该资源的 apiGroup，并在改动后重下发。集群返回: "),

    /** 资源由平台组件或中间件实例管理：通用资源页不允许修改/删除（spec §7） */
    RESOURCE_MANAGED_BY_PLATFORM(10048, "该资源由平台组件或中间件实例管理，不能在通用资源页修改或删除；请到对应页面操作"),

    /** PrometheusRule 内容不合法（alert/record 二选一、expr 必填、组名必填） */
    PROMETHEUS_RULE_INVALID(10045, "PrometheusRule 内容不合法"),

    /** Alertmanager webhook 的共享密钥不对（HTTP 状态码由控制器直接给，不靠全局异常映射） */
    ALERT_WEBHOOK_UNAUTHORIZED(10044, "告警接收端点鉴权失败"),

    /** 通知方式参数非法（code 不合法 / 类型不支持 / 配置缺字段） */
    NOTIFY_CHANNEL_INVALID(10046, "通知方式参数非法"),

    /** 通知方式仍被规则绑定，不能删除 */
    NOTIFY_CHANNEL_IN_USE(10047, "该通知方式仍被规则使用，不能删除"),

    ERROR(5000,"服务端异常"),


    OAUTH2_JWT_NEED_SECRET(90001,"jwt 对称算法需要配置"),

    OAUTH2_JWK_SOURCE_BEAN_FAILED(90002, "初始化 JWKSource 失败"),

    OAUTH2_CLIENT_NOT_EXIST(90003, "oauth2 client 不存在"),

    OAUTH2_CLIENT_AUTH_CODE_NEED_SECRET(90004, "授权码模式下必须填写 Client Secret"),
    OAUTH2_CLIENT_AUTH_CODE_NEED_REDIRECT(90005, "授权码模式下必须填写授权回调地址"),
    OAUTH2_CLIENT_AUTH_METHOD_REQUIRED(90006, "认证方式为必填项（仅设备码模式可留空）"),
    OAUTH2_JWS_SIG_ALG_REQUIRED(90007, "选择 JWS 时签名算法为必填项"),
    OAUTH2_JWS_SYMMETRIC_NEED_SECRET(90008, "JWS 对称签名算法必须填写签名密钥"),
    OAUTH2_JWE_KEY_ALG_REQUIRED(90009, "选择 JWE 时密钥加密算法为必填项"),
    OAUTH2_JWE_ASYMMETRIC_NEED_JWK_URL(90010, "JWE 非对称加密必须填写公钥地址"),
    OAUTH2_JWE_SYMMETRIC_NEED_SECRET(90011, "JWE 对称加密算法必须填写对称密钥"),

    OAUTH2_JWS_SECRET_DECODE_ERROR(90012, "JWS 对称密钥解密失败"),

    OAUTH2_JWS_KEY_LENGTH_INSUFFICIENT(90013, "JWS 对称签名密钥长度不足: "),

    OAUTH2_JWE_KEY_LENGTH_MISMATCH(90014, "JWE 对称加密密钥长度不匹配: "),

    OAUTH2_CLIENT_TOKEN_TTL_EXCEEDS_SESSION(90015, "Access Token 有效期必须小于会话空闲超时: ")

    ;


    EnumResponseType(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    private final Integer code;

    private final String msg;

    public static String getMsgByCode(Integer code){
        for(EnumResponseType e: EnumResponseType.values()){
            if(e.getCode().equals(code)){
                return e.getMsg();
            }
        }
        return null;
    }
}
