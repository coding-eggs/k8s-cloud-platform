package com.coding.common.models.middleware;

import lombok.Data;

/**
 * platform-api → k8s-server：下发一个中间件端点的对外暴露对象（零业务：参数怎么给就怎么建）。
 *
 * <ul>
 *   <li>{@code mode=GATEWAY}：建 {@code protocol} 对应的路由（HTTP → HTTPRoute，TCP → TCPRoute），挂到
 *       {@code listenerSetName/sectionName}，backend = {@code serviceName:servicePort}；删另一种路由与 NodePort Service；</li>
 *   <li>{@code mode=NODEPORT}：建 NodePort Service（selector / targetPort 抄目标 Service），nodePort = {@code nodePort}；删路由；</li>
 *   <li>{@code mode=NONE}：三种对象都删。</li>
 * </ul>
 * 对象名 {@code <instanceName>-expose-<endpointKey>}，标签 {@code platform.coding/middleware-expose=<instanceName>}。
 * delete-all 只用 clusterId / namespace / instanceName / tenantId。
 */
@Data
public class MiddlewareExposeRequestDTO {

    private String clusterId;

    private String namespace;

    /** 管理员代管轨的租户 */
    private String tenantId;

    private String instanceName;

    private String endpointKey;

    /** NONE / GATEWAY / NODEPORT */
    private String mode;

    private String serviceName;

    private Integer servicePort;

    /** HTTP / TCP（GATEWAY 时） */
    private String protocol;

    private String listenerSetName;

    private String sectionName;

    /** NODEPORT 时 */
    private Integer nodePort;
}
