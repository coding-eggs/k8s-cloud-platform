package com.coding.platformapi.services;

import com.coding.data.mapper.auth.PlatformPermissionMapper;
import com.coding.data.mapper.system.PlatformAuditLogMapper;
import com.coding.data.models.auth.PlatformPermission;
import com.coding.data.models.system.PlatformAuditLog;
import com.coding.data.models.system.PlatformAuditLogQuery;
import com.coding.common.audit.AuditAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Date;
import java.util.List;

/**
 * 审计日志页的后端：查询 + 清理 + 动作码候选。
 *
 * <p>写入（fail-open 门面、拒绝记录、独立事务）在 platform-common 的
 * {@code AuditRecorder} / {@code AuditWriter}，api 与 auth 共用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final int DEFAULT_LIMIT = 50;

    private static final int MAX_LIMIT = 200;

    /**
     * 候选动作码上限。
     *
     * <p><b>上界的推导</b>（决定这个数够不够）：候选 = {@code AuditAction} 常量（今天 23 个）
     * ∪ API 域**权限码去重后**的集合（DENIED 记录的 {@code action} 存的是权限码）。
     * API 权限**行**有 262 行，但多行共享同一个码（如 `/audit/list` 与 `/audit/actions` 都是
     * `platform:audit:read`），所以码数远少于行数 —— 2026-10-13 **实测 137**（23 动作码 + 114 权限码）。
     * 上界 ≈ 23 + 262 ≈ 285（去重后必 ≤ 行数），取 2000 是留 7 倍余量，避免后续增长后悄悄触顶。
     *
     * <p><b>为什么取「可能值全集」而不是「审计表里的观测值」</b>：观测值在新装 / 刚上线时几乎是空的
     * （表里只有一两条记录 → 下拉只剩一两个候选），筛选根本用不了。可能值全集与数据量无关、开箱即用；
     * 代价是下拉里会包含**从未被使用过**的码，选中后查不到数据 —— 这是显式接受的。
     *
     * <p><b>为什么不做成「过滤下推」</b>：{@code action} 列**没有索引**（索引只有 occurred_at / actor /
     * cluster_ns / result），{@code where action like '%q%' group by action} 会退化成每次按键一次
     * 全表扫描 + 分组（表按年级别增长）。而前端在几百条短字符串上做 includes 是微秒级 ——
     * **下推不是优化，是劣化**。
     */
    private static final int MAX_ACTION_OPTIONS = 2000;

    private final PlatformAuditLogMapper mapper;

    /** 动作码候选里的权限码来自权限表（与 PermissionRegistryFactory 同一读法） */
    private final PlatformPermissionMapper permissionMapper;

    /** 查询：limit 由本层钳制，调用方不必关心上限 */
    public List<PlatformAuditLog> query(PlatformAuditLogQuery q) {
        clamp(q);
        return mapper.query(q);
    }

    /** 删除：闭开区间 [from, to)；返回实际删除行数 */
    public int purge(Date from, Date to) {
        return mapper.deleteByTimeRange(from, to);
    }

    /**
     * 动作码候选值 = {@code AuditAction} 的全部动作码 ∪ API 域权限码，供筛选 autocomplete。
     *
     * <p>顺序：动作码在前（筛选的主目标），权限码在后；各组内部字母序 —— 空查询时的顺序可预期。
     * 两者理论上不重叠，仍用 {@code Set} 去重防呆。
     */
    public List<String> listActions() {
        Set<String> merged = new LinkedHashSet<>(AuditAction.all());
        // 与 PermissionRegistryFactory 同一口径：DENIED 记录的 action 是被判的权限码，只可能是 API 域
        permissionMapper.selectAllActive().stream()
                .filter(p -> "API".equals(p.getDomain()))
                .map(PlatformPermission::getCode)
                .sorted()
                .forEach(merged::add);
        if (merged.size() > MAX_ACTION_OPTIONS) {
            log.warn("审计动作码候选已达上限 {}，本次返回的候选不完整；"
                    + "说明动作码常量或权限行超出预期，需重新评估（加大上限或改为下推过滤）",
                    MAX_ACTION_OPTIONS);
            return merged.stream().limit(MAX_ACTION_OPTIONS).toList();
        }
        return List.copyOf(merged);
    }

    private void clamp(PlatformAuditLogQuery q) {
        Integer limit = q.getLimit();
        if (limit == null || limit <= 0) {
            q.setLimit(DEFAULT_LIMIT);
        } else if (limit > MAX_LIMIT) {
            q.setLimit(MAX_LIMIT);
        }
    }

    /**
     * 客户端 IP：优先 X-Forwarded-For 的第一个（平台通常经网关），退化到 remoteAddr。
     */
}
