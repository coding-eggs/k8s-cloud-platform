package com.coding.k8score.operations;

/**
 * 平台写 K8s 的统一 Server-Side Apply（SSA）约定。所有 {@code Operations.update()} 用它替换全量 PUT。
 * <p>为什么用 SSA 而不是全量 {@code .update()}：
 * <ul>
 *   <li><b>保留未建模字段</b>——converter 只产出平台管理的字段（apply config），apiserver 对
 *       apply config 里没出现的字段原样保留。修复了旧全量 PUT「DTO 没建模的字段被抹掉」的问题
 *       （典型：ServiceMonitor endpoint 的 {@code bearerTokenFile}）。</li>
 *   <li><b>清空语义</b>——省略一个平台已拥有的字段 = 删除该字段。</li>
 *   <li><b>原子、无 read-modify-write 竞态</b>，无需 resourceVersion 乐观锁。</li>
 * </ul>
 * 各站点调用形态：{@code .resource(obj).fieldManager(FIELD_MANAGER).forceConflicts().serverSideApply()}。
 */
public final class ServerSideApply {

    /**
     * 平台统一 fieldManager 身份。写入后出现在对象 {@code metadata.managedFields} 里，
     * 可据此查清「哪个字段是平台最后改的」。全平台统一一个名字，便于审计与排查。
     */
    public static final String FIELD_MANAGER = "platform-system";

    private ServerSideApply() {
    }
}
