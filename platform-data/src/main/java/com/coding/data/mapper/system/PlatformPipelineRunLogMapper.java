package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformPipelineRunLog;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 运行日志的读写（表 {@code platform_pipeline_run_log}）。
 *
 * <p>⚠️ 唯一索引是 {@code (run_id, node_name)} —— 归档重投时**不重写**（先查再插，或靠
 * ON DUPLICATE KEY）。日志行只增不减，删只在保留清理与「连带删运行」时发生。
 */
@Repository
public interface PlatformPipelineRunLogMapper {

    int insert(PlatformPipelineRunLog log);

    List<PlatformPipelineRunLog> listByRunId(@Param("runId") String runId);

    /** 删一条运行的日志（连带删除靠代码，本仓不加外键） */
    int deleteByRunId(@Param("runId") String runId);

    /** 批量删：保留清理时先删日志行、再删运行行 */
    int deleteByRunIds(@Param("runIds") List<String> runIds);

    /** 取某条运行的某个节点的日志行（**取消前抢抓**时用它判断是否已有） */
    PlatformPipelineRunLog selectByRunIdAndNode(@Param("runId") String runId,
                                                @Param("nodeName") String nodeName);
}
