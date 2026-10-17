package com.coding.platformapi.models;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;

/**
 * 告警列表请求体：筛选 + 游标分页（照 {@code AuditQueryRequest}）。
 *
 * <p>游标 = 上一页最后一条的 {@code (startsAt, id)}；首页两个游标字段留空。
 * 用游标而非 offset：告警表持续追加，offset 翻页会漏读/重读。
 *
 * <p>⚠️ 不带 tenantId —— 租户边界只从 token 的 tenantInfo 取（入参一概不算数）。
 */
@Data
public class AlertQueryRequest {

    /** 必填 */
    private String clusterId;

    private Date from;

    /** 开区间上界：只取 startsAt &lt; to 的记录 */
    private Date to;

    private String namespace;

    /** firing | resolved */
    private String status;

    private String severity;

    /** 模糊匹配 */
    private String alertname;

    /** 默认 false（即默认排除 Watchdog 心跳告警，见 spec §7.3） */
    private Boolean showWatchdog;

    /**
     * 首页留空。
     *
     * <p>⚠️ 必须按**毫秒**解析：这个值来自上一页最后一条的 {@code startsAt}，而
     * {@code starts_at} 列是 {@code DATETIME(3)}。全局默认格式（{@code yyyy-MM-dd HH:mm:ss}）
     * 会把 {@code .123} 静默丢掉，于是游标条件 {@code starts_at = cursor} 永远不成立 ——
     * 「加载更多」返回 0 行。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private Date cursorStartsAt;

    /** 首页留空 */
    private Long cursorId;

    /** 单页条数；服务端钳制在 [1,200]，默认 50 */
    private Integer limit;
}
