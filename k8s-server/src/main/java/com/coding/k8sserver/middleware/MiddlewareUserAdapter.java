package com.coding.k8sserver.middleware;

import com.coding.common.models.middleware.MiddlewareUserView;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.util.List;

/**
 * 一款中间件的用户管理实现。
 *
 * <p>与 {@link MiddlewareAdapter} 同形：**不抽公共基类**，只约定最小接口面，按 code 分派。
 * 执行通道各家不同（PG 走 CRD、ES/RabbitMQ/Milvus 走 HTTP、MySQL 走 Pod exec、Redis 走 Secret+重启），
 * 由实现自己决定。
 */
public interface MiddlewareUserAdapter {

    String code();

    /** 账号列表（内置账号 + 平台建的）；不返回明文口令 */
    List<MiddlewareUserView> list(KubernetesClient client, MiddlewareUserRef ref);

    void create(KubernetesClient client, MiddlewareUserRef ref, String username, String password, String role);

    void drop(KubernetesClient client, MiddlewareUserRef ref, String username);

    void changePassword(KubernetesClient client, MiddlewareUserRef ref, String username, String newPassword);

    /** 可选角色（只读 / 读写 / 管理员…）；没有角色概念的实例返回空列表 */
    default List<String> roles(MiddlewareUserRef ref) {
        return List.of();
    }
}
