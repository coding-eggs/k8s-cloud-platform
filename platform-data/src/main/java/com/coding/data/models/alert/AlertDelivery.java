package com.coding.data.models.alert;

import lombok.Data;

import java.util.Date;

/** 一条告警 × 一个渠道 × 一个状态 = 一次投递（表 platform_alert_delivery）。 */
@Data
public class AlertDelivery {
    private Long id;
    private Long eventId;
    private String channelCode;
    /** 投递时的告警状态 firing/resolved */
    private String alertStatus;
    /** PENDING/SENT/FAILED */
    private String status;
    private Integer attempts;
    private String lastError;
    private Date createdAt;
    private Date sentAt;
}
