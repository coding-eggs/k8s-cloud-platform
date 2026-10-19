package com.coding.data.models.alert;

import lombok.Data;

import java.util.Date;

/** 规则 → 通知方式/收件人（表 platform_rule_notify，CR 级）。 */
@Data
public class RuleNotify {
    private String id;
    private String clusterId;
    private String namespace;
    private String ruleName;
    private String channelCode;
    /** 用户 id 数组的 JSON 串 */
    private String userIds;
    private Date createdAt;
    private Date updatedAt;
}
