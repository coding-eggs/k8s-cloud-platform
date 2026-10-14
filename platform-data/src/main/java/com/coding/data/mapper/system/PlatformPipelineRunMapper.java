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

    /** 运行历史（新→旧）。**只读库** —— 集群里的对象归档后已经删了 */
    List<PlatformPipelineRun> listByPipeline(@Param("pipelineId") String pipelineId,
                                             @Param("clusterId") String clusterId,
                                             @Param("namespace") String namespace);

    /** 保留清理：把候选行捞出来（**时间比较在 Java 侧做**，见 PipelineRetentionService） */
    List<PlatformPipelineRun> listAll();
}
