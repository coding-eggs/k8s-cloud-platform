package com.coding.data.models.auth;

import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

@Data
public class NamespaceRequest {
    private String id;
    private String tenantId;
    private String applicant;
    private String kind;
    private String allocationId;
    private String namespace;
    private String clusterId;
    private String purpose;
    private Long expiresAt;
    private String quotaKey;
    private BigDecimal baseline;
    private BigDecimal targetValue;
    private BigDecimal approvedValue;
    private String reviewer;
    private String reason;
    private String status;
    private Date createdAt;
}
