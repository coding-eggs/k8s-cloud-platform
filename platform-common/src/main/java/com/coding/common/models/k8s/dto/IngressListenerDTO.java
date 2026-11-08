package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * 应用端口池里的一个 listener（平台生成的命名空间 ListenerSet 用）。
 *
 * <p>名称、端口、协议都由 platform-api 按端口分配表算好；k8s-server 只校验格式。
 */
@Data
public class IngressListenerDTO {
    /** listener 名（平台固定 p&lt;端口&gt;，路由的 sectionName 指向它） */
    private String name;
    private Integer port;
    /** HTTP / TCP（端口方式没有域名，不提供 HTTPS） */
    private String protocol;
}
