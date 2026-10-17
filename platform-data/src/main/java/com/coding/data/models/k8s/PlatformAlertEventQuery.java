package com.coding.data.models.k8s;

import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 告警查询条件：筛选 + 游标分页（照 {@code PlatformAuditLogQuery} 的范式）。
 *
 * <p><b>为什么用游标而不是 offset</b>：告警表持续追加，翻页期间新告警不断写入，
 * offset 会导致漏读/重读。游标 = 上一页最后一条的 {@code (startsAt, id)}。
 *
 * <p>放在 platform-data（而不是复用 platform-api 的请求 DTO）：api 层可以依赖 data 层，反向不行。
 */
@Data
public class PlatformAlertEventQuery {

    /** 必填（端点一定带上下文） */
    private String clusterId;

    /**
     * 租户侧收窄用。<b>null = 不限（管理员）；空 list = 一条都不返回</b> ——
     * 后者不能退化成"不过滤"，那会让一个没有任何命名空间分配的租户看到全平台告警。
     */
    private List<String> namespaces;

    /** firing | resolved */
    private String status;

    private String severity;

    private String namespace;

    /** 模糊匹配 */
    private String alertname;

    /** 默认 true：心跳告警不进首屏（spec §7.3） */
    private Boolean excludeWatchdog;

    /** starts_at &gt;= */
    private Date from;

    /** starts_at &lt; */
    private Date to;

    // ---- 游标（首页为 null） ----

    private Date cursorStartsAt;

    private Long cursorId;

    /** 单页条数；由 service 钳制在 [1,200]，默认 50 */
    private Integer limit;
}
