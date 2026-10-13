package com.coding.platformapi.models;

import lombok.Data;

import java.util.Date;

/**
 * 按时间范围删除审计日志：闭开区间 {@code [from, to)}。
 */
@Data
public class AuditPurgeRequest {

    private Date from;

    /** 开区间上界：只删 occurredAt &lt; to 的记录 */
    private Date to;
}
