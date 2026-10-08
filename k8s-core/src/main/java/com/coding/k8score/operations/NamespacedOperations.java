package com.coding.k8score.operations;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;

import java.util.List;

/**
 * 命名空间级别的资源操作（如 Deployment、RoleBinding、ServiceAccount）
 */
public interface NamespacedOperations<T> extends ResourceOperations<T> {

    /**
     * 查询列表；labelSelector 为 K8s 原生标签选择器语法，原样透传，空则不加过滤
     */
    List<T> list(String namespace, String labelSelector, String fieldSelector);

    /**
     * 跨全部命名空间列举（平台侧动作，{@code inAnyNamespace()} 语义）。
     * <p>
     * <b>只有 admin client 能真正执行</b>：租户 SA 只有 per-namespace 的 RoleBinding，集群范围的 list 需要
     * 集群级权限，用租户 client 调会被 K8s 直接 Forbidden —— 是<b>响亮</b>的失败，不是静默只返回自己的那些。
     * 调用方侧的身份闸门在 k8s-server 的 {@code ResourceAccessResolver#assertPlatformSide}
     * （要求 PLATFORM_SCOPE），暴露闸门在 platform-api 的权限表（配了码才有路径可达）。
     * <p>
     * <b>本方法实现与否 = 本资源是否支持跨命名空间列举</b>：默认实现抛业务异常，绝不静默降级成
     * 单命名空间 list（那会把"不支持"伪装成"支持"，返回一份看起来正常却不完整的数据）。
     * 新增支持只需覆写本方法 + 在 platform-api 侧加路径与权限行。
     */
    default List<T> listAll(String labelSelector, String fieldSelector) {
        throw new CloudPlatformException(EnumResponseType.OPERATION_NOT_SUPPORTED,
                "本资源不支持跨命名空间列举（" + getClass().getSimpleName() + " 未实现 listAll）");
    }

    T get(String namespace, String name);

    void delete(String namespace, String name);

    String yaml(String namespace, String name);

}