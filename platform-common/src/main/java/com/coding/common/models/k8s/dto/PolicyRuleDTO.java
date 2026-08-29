package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "RBAC 策略规则，定义对资源的访问权限")
public class PolicyRuleDTO {

    @Schema(description = "API 组列表，空字符串表示核心 API 组，* 表示所有", example = "[\"\"]")
    private List<String> apiGroups;

    @Schema(description = "资源类型列表，例如 pods、services", example = "[\"pods\", \"services\"]")
    private List<String> resources;

    @Schema(description = "允许的操作列表，例如 get、list、create、update、delete", example = "[\"get\", \"list\", \"create\"]")
    private List<String> verbs;

    @Schema(description = "资源名称白名单（可选），为空表示所有")
    private List<String> resourceNames;

    @Schema(description = "非资源 URL 列表，仅适用于 ClusterRoleBinding")
    private List<String> nonResourceURLs;

}