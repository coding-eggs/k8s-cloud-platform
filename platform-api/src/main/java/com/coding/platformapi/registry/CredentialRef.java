package com.coding.platformapi.registry;

/**
 * 一次方言调用的入参：一条已解密的 registry 连接。
 *
 * <p>⚠️ <b>本记录携带明文凭据</b>（{@code username} / {@code secret}，由
 * {@code CredentialService.resolveSecret} 解出）。它是**进程内**传参用的，
 * <b>不得出现在任何响应体里，也不得被日志打印</b>。
 */
public record CredentialRef(String id, String name, String type, String endpoint,
                            String username, String secret) {
}
