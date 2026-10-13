package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformAuditLog;
import com.coding.data.models.system.PlatformAuditLogQuery;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

/**
 * 审计日志读写。三操作：写入 / 条件查询（含游标）/ 按时间范围删除。
 *
 * <p>没有 update 与单条 get：审计记录**只追加不改**；列表本就返回全部字段，
 * 详情由前端行展开，不需要单条端点。
 */
@Repository
public interface PlatformAuditLogMapper {

    int insert(PlatformAuditLog record);

    List<PlatformAuditLog> query(PlatformAuditLogQuery query);

    /** 闭开区间 [from, to)，任一为 null 则该侧不限；返回删除行数 */
    int deleteByTimeRange(@Param("from") Date from, @Param("to") Date to);

}
