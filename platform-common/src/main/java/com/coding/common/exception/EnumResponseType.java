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
