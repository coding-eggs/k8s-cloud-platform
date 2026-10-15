package com.coding.common.models.pipeline;

/**
 * 一条**被授权、且已解密**的平台连接，随画布一起交给渲染方。
 *
 * <p>⚠️ <b>它带明文凭据，所以有两件事绝不能发生</b>：
 * <ol>
 *   <li><b>不能进渲染结果</b> —— 凭据一旦写进 step 的 script，就会出现在 PipelineRun 的 spec 里，
 *       而 {@code /pipeline/preview} 会把渲染结果**原样给用户看**。所以脚本只读**挂进来的文件**，
 *       凭据本身只用于在集群里建一个 Secret。</li>
 *   <li><b>不能进日志与预览路径</b> —— {@code render} 与 {@code renderYaml} 的入参**不带**连接；
 *       只有「创建运行」那条路径才带。</li>
 * </ol>
 *
 * <p>它在**同机回环**上走一趟（platform-api → k8s-server），用的是已有那条带共享密钥之外的服务内通道；
 * 不落盘、不缓存。
 */
public record PipelineConnectionRef(String id, String endpoint, String username, String secret) {
}
