package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformPipelineRun;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 流水线运行记录的读写（表 {@code platform_pipeline_run}）。
 *
 * <p>⚠️ {@link #selectByRunName} 是**归档幂等**的入口：回调可能重投（k8s-server 重试 /
 * 平台重启后补投），靠它判断「这条运行归档过没有」。
 */
@Repository
public interface PlatformPipelineRunMapper {

    int insert(PlatformPipelineRun r);

    /** 归档时写状态与耗时。**只写这几列** —— run_name 与 pipeline_id 是身份，不在这里改 */
    int update(PlatformPipelineRun r);

    int deleteById(@Param("id") String id);

    PlatformPipelineRun selectById(@Param("id") String id);

    /** 归档幂等键。空结果 = 还没归档过 */
    PlatformPipelineRun selectByRunName(@Param("runName") String runName);

    /** 运行历史（新→旧）。**只读库** —— 集群里的对象归档后已经删了。
     *  ⚠️ **不带 {@code canvas_json} / {@code run_yaml} 两个大字段**（列表几十条，不该每条都背着
     *  画布与 YAML）；要看它们走 {@link #selectByRunName}。 */
    List<PlatformPipelineRun> listByPipeline(@Param("pipelineId") String pipelineId,
                                             @Param("clusterId") String clusterId,
                                             @Param("namespace") String namespace);

    /** 全部（跨命名空间/流水线）。保留清理与启动对账用 */
    List<PlatformPipelineRun> listAll();

    /**
     * 所有**仍标记为 RUNNING** 的行 —— 孤儿对账的候选。
     *
     * <p>⚠️ 常态下只有 0~1 条：平台判超时的上限是 30 分钟，正常运行不会在里面久留。
     * 对账器拿它逐条去集群里核对对象是否还活着。
     */
    List<PlatformPipelineRun> listRunning();

    /**
     * 把一条「集群对象已经不在」的运行结案（{@code FAILED}）。
     *
     * <p>⚠️ **只写这三列**，且 {@code where} 带着 {@code status = 'RUNNING'}：
     * 一来幂等（重投/对账重复调用只生效一次），二来绝不会拿空值盖掉已经归档的
     * {@code steps_json} / {@code run_yaml}（通用的 {@link #update} 会把它们一起写成 null）。
     *
     * @return 受影响行数；0 = 这行已经结过案，不归它管了
     */
    int markLost(@Param("runName") String runName,
                 @Param("reason") String reason,
                 @Param("completionTime") String completionTime);

    /**
     * 正在跑的运行占用的工作区 PVC 名（去重）。
     *
     * <p>⚠️ 「待重试工作区」的列表与清理都要把它们排除 —— 工作区在运行**创建时**就存在，
     * 不过滤的话在途运行的卷会被当成「失败残留」删掉。链上的子运行共用父运行那一块，所以去重。
     */
    List<String> selectRunningWorkspacePvcs(@Param("clusterId") String clusterId,
                                            @Param("namespace") String namespace);

    /**
     * 保留清理的候选：{@code completion_time} 早于 cutoff 的。
     *
     * <p>⚠️ <b>这是字符串比较</b>（该列是 varchar，存 Tekton 的 RFC3339 原文）。
     * 它成立的前提是「所有值都是 UTC、都是 {@code Z} 结尾、都不带小数秒」——
     * Tekton 的 metav1.Time 正是这样序列化的。cutoff 也必须是同格式
     * （{@code Instant.toString()} 就是），否则比出来的是字典序而不是时间序。
     * 未终结（completion_time 为 null）的行**不在候选里** —— 它们还在跑。
     */
    List<PlatformPipelineRun> selectCompletedBefore(@Param("cutoff") String cutoff);

    /** 批量删（连带删日志靠代码，本仓不加外键） */
    int deleteByIds(@Param("ids") List<String> ids);
}
