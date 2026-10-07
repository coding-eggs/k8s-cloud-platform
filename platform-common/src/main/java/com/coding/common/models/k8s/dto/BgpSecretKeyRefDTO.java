package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * BGP 密码 Secret 引用（projectcalico.org/v3 {@code spec.password.secretKeyRef} /
 * {@code spec.nodeMeshPassword.secretKeyRef}）。只存引用，平台不读、不展示密文。
 */
@Data
public class BgpSecretKeyRefDTO {

    /** Secret 名（节点 Pod 所在命名空间内） */
    private String name;

    /** Secret 命名空间 */
    private String namespace;

    /** Secret 中的 key */
    private String key;

}
