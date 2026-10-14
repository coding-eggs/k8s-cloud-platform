package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformPipeline;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 流水线定义的读写（表 {@code platform_pipeline}）。
 *
 * <p><b>查询一律带 clusterId + namespace</b>：流水线是命名空间级资源，没有「列出全部集群的流水线」
 * 这个动作（与 Deployment 一样）。租户边界靠这两个字段 + 分配表，不靠这张表里的租户列。
 */
@Repository
public interface PlatformPipelineMapper {

    int insert(PlatformPipeline p);

    int update(PlatformPipeline p);

    int deleteById(@Param("id") String id);

    PlatformPipeline selectById(@Param("id") String id);

    /** 唯一索引 uk_pipeline 的那三列 —— 建/改时判重也用它 */
    PlatformPipeline selectByKey(@Param("clusterId") String clusterId,
                                 @Param("namespace") String namespace,
                                 @Param("name") String name);

    List<PlatformPipeline> listByNamespace(@Param("clusterId") String clusterId,
                                           @Param("namespace") String namespace);
}
