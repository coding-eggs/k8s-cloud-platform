package com.coding.common.models.pipeline;

/**
 * **构建缓存**的状态：每个命名空间一块长期卷，跑完不删（见 {@code PipelineRunOperations.CACHE_PVC}）。
 *
 * <p>它随 {@code /pipeline/capability} 一起回 —— 因为「这个集群能不能开缓存」与
 * 「这个集群能不能跑 CI」是同一类问题（都是"环境给不给得了"），
 * 而且页面本来就为前者发一次请求；分成两个端点只会多一次往返、多一条权限行。
 *
 * @param ready      现在挂得上吗 —— 决定**下次运行会不会用上缓存**
 * @param exists     那块卷在不在。存在却没 ready = 建了却绑不上（通常是存储不支持共享卷 RWX）
 * @param claimName  卷名 —— 「清缓存」删的就是它
 * @param capacity   容量（没绑上时回的是配置值）
 * @param reason     {@code ready=false} 时的一句人话；ready 时为 null
 * @param prerequisite    集群前提那句话说清现在的值（例如 {@code Tekton coschedule = pipelineruns}）
 * @param prerequisiteOk  前提满足吗。**不满足时 ready 一定为 false，但卷可能好好地在那儿** ——
 *                        那种"卷没问题却用不了"的形态最容易让人以为平台坏了，所以两件事分开报
 */
public record PipelineCacheStatusDTO(boolean ready, boolean exists, String claimName,
                                     String capacity, String reason,
                                     String prerequisite, boolean prerequisiteOk) {

    /**
     * 只答**卷**那一半时的形态（k8s-server 侧就只答这个 —— 集群前提由 platform-api 组装，
     * 因为它要问的是另一个端点）。{@code prerequisiteOk=true} 表示"没问，不当作否"。
     */
    public PipelineCacheStatusDTO(boolean ready, boolean exists, String claimName,
                                  String capacity, String reason) {
        this(ready, exists, claimName, capacity, reason, null, true);
    }
}
