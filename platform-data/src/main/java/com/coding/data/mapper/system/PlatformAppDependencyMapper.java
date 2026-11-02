package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformAppDependency;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

/** 应用的依赖（表 platform_app_dependency）。 */
@Repository
public interface PlatformAppDependencyMapper {

    List<PlatformAppDependency> selectByApp(@Param("appId") String appId);

    PlatformAppDependency selectByKey(@Param("appId") String appId, @Param("depKey") String depKey);

    int insert(PlatformAppDependency record);

    int updateById(PlatformAppDependency record);

    int deleteById(@Param("id") Long id);

    /**
     * 初始化开始：把状态置 RUNNING 并清掉上次日志（updateById 不能置空列，所以单独一条）。
     * 已经是 RUNNING 时返回 0，调用方据此拒绝并发执行。
     */
    int startInit(@Param("id") Long id, @Param("now") Date now);

    /** 初始化结束（只在仍是 RUNNING 时生效）；hash 为空表示保留上次成功的摘要。 */
    int finishInit(@Param("id") Long id, @Param("status") String status, @Param("hash") String hash,
                   @Param("log") String log, @Param("now") Date now);

    /** 把 RUNNING 的初始化置 FAILED；before 非空时只处理 updated_at 早于它的（定时清扫用）。 */
    int failRunningInit(@Param("message") String message, @Param("before") Date before,
                        @Param("now") Date now);
}
