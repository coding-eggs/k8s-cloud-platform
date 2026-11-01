package com.coding.platformapi.models;

import java.util.List;

/**
 * 按 URL 反查的结果：命中的规则行按 code 分组。
 *
 * <p>{@code totalRules} 是命中规则总数（= matched 里各组 rules 之和）；
 * 前端用它显示「命中 N 条」，并在为 0 时给空状态。
 */
public record PermissionMatchResponse(String method, String path, int totalRules, List<PermissionGroup> matched) {
}
