package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/**
 * 审计日志查询条件：筛选 + 游标分页。
 *
 * <p><b>为什么用游标而不是 offset</b>：审计表持续追加，翻页期间新记录不断写入，
 * offset 会导致漏读/重读。游标 = 上一页最后一条的 {@code (occurredAt, id)}。
 *
 * <p>放在 platform-data（而不是复用 platform-api 的请求 DTO）：api 层可以依赖 data 层，
 * 反向不行。
 */
@Data
public class PlatformAuditLogQuery {

    // ---- 筛选（全部可选） ----

    private Date from;

    /** 开区间上界：只取 occurredAt &lt; to 的记录 */
    private Date to;

    private String actorUsername;

    private String clusterId;

    private String namespace;

    /** SUCCESS / FAILED / DENIED */
    private String result;

    /** 动作码，或 DENIED 记录里的权限码 */
    private String action;

    // ---- 游标（首页为 null） ----

    private Date cursorOccurredAt;

    private Long cursorId;

    /** 单页条数；由 AuditService 钳制在 [1,200]，默认 50 */
    private Integer limit;
}
