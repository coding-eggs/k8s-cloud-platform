package com.coding.platformapi.models;

import lombok.Data;

import java.util.Date;

/**
 * 审计列表请求体：筛选 + 游标分页。
 *
 * <p>游标 = 上一页最后一条的 {@code (occurredAt, id)}；首页两个游标字段留空。
 * 用游标而非 offset：审计表持续追加，offset 翻页会漏读/重读。
 */
@Data
public class AuditQueryRequest {

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

    /** 首页留空 */
    private Date cursorOccurredAt;

    /** 首页留空 */
    private Long cursorId;

    /** 单页条数；服务端钳制在 [1,200]，默认 50 */
    private Integer limit;
}
