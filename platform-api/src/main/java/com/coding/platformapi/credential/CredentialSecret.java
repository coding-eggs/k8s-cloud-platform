package com.coding.platformapi.credential;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 认证字段的**明文**载体，也是 {@code secret_encrypted} 解密后的 JSON 结构。
 *
 * <p>v1 只有 BASIC 一种，所以只有这两个字段。加 SSH 是在这里加字段 —— 表结构不动（spec §4）。
 *
 * <p>⚠️ 本类的实例**不得出现在任何响应体里**。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CredentialSecret {

    private String username;

    private String secret;
}
