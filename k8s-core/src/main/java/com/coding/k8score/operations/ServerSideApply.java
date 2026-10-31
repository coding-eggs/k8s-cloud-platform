package com.coding.k8score.operations;

/**
 * 平台写 K8s 的统一 Server-Side Apply（SSA）约定。<b>create 与 update 都必须用它</b>。
 * <p>为什么用 SSA 而不是全量 {@code .update()}：
 * <ul>
 *   <li><b>保留未建模字段</b>——converter 只产出平台管理的字段（apply config），apiserver 对
 *       apply config 里没出现的字段原样保留。修复了旧全量 PUT「DTO 没建模的字段被抹掉」的问题
 *       （典型：ServiceMonitor endpoint 的 {@code bearerTokenFile}）。</li>
 *   <li><b>清空语义</b>——省略一个平台已拥有的字段 = 删除该字段。</li>
 *   <li><b>原子、无 read-modify-write 竞态</b>，无需 resourceVersion 乐观锁。</li>
 * </ul>
 * 各站点调用形态：{@code .resource(obj).fieldManager(FIELD_MANAGER).forceConflicts().serverSideApply()}。
 *
 * <h2>create 为什么也必须走 SSA</h2>
 * fabric8 的 {@code create()} 是普通 POST，<b>URL 上不带 {@code fieldManager}</b>
 * （{@code OperationSupport.handleCreate} 只追加 dryRun / fieldValidation），apiserver 只能按客户端
 * 身份登记一个别的 manager。后果在 <b>map list</b> 上暴露：
 * <ul>
 *   <li>SSA 的「省略即删除」只对<b>本 manager 拥有</b>的条目生效；条目只要还有一个别的 owner，
 *       怎么 apply 都删不掉（实测 2026-11-01：一个端口条目同时被 {@code fabric8-kubernetes-client}
 *       与 {@code platform-system} 持有，两边分别 apply 都改不了它）。</li>
 *   <li>于是把条目的 key 换掉（Service 的 {@code port} 改号、容器/env/volume 改名、Gateway 的
 *       listener 改名）就会「旧条目留着 + 新条目加上」，报 {@code duplicate nodePort} /
 *       {@code Duplicate value}，或者静默多出一条。</li>
 * </ul>
 * 用 SSA 创建后，对象从出生起就只有 {@code platform-system} 一个 owner，后续 update 的
 * 「省略即删除」才真正成立（已用单一 owner 的临时对象验证：改名后只剩一条）。
 *
 * <p>例外见 {@code PipelineRunOperations}：那几个 create 依赖「必须新建」语义（409 = AlreadyExists
 * 是流程的一部分），不是 upsert。
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
