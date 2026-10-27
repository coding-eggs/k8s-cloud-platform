package com.coding.platformapi.registry;

/**
 * 一次方言调用的入参：一条已解密的 registry 连接。
 *
 * <p>⚠️ <b>本记录携带明文凭据</b>（{@code username} / {@code secret}，由
 * {@code CredentialService.resolveSecret} 解出）。它是**进程内**传参用的，
 * <b>不得出现在任何响应体里，也不得被日志打印</b>。
 */
public record CredentialRef(String id, String name, String type, String endpoint,
                            String username, String secret,
                            /**
                             * 这条凭据归属的 Harbor project（平台建的租户项目才有）。
                             *
                             * <p>项目级 robot 没有「列出全部 project」的权限，浏览第一层不能去调
                             * {@code GET /projects}（会 403）—— 归属已知时直接用这个值。
                             */
                            String project) {

    /** 不带 project 的便捷构造：用户手工建的连接没有 project 归属。 */
    public CredentialRef(String id, String name, String type, String endpoint,
                         String username, String secret) {
        this(id, name, type, endpoint, username, secret, null);
    }
}
