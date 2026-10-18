package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对一个 Secret 的引用（namespace + name）。
 *
 * <p>凭证只以引用形式进入表单与快照；密码永远不进 values / {@code __platform}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SecretRef {
    private String namespace;
    private String name;
}
