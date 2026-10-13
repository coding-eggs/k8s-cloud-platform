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

    /**
     * 审计表里**实际出现过**的动作码（distinct），按出现次数倒序 —— 供筛选 autocomplete 做候选值。
     *
     * <p>为什么从数据里取而不是维护一份码表：码表会与 {@code AuditAction} 漂移，且"哪些码还在用"
     * 只有数据知道。同族先例：{@code /servicemonitors/relabel-labels} 等候选值端点。
     */
    List<String> listActions(@Param("limit") int limit);
}
