package com.coding.common.models.k8s.dto;

import java.util.List;

/**
 * 模板规则 JSON 的包装结构（platform_rbac_template.rules 列 = {"rules":[...]}）
 */
public record RulesPayload(List<PolicyRuleDTO> rules) {
}
