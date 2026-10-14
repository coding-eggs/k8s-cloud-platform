package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 补全结果。每一条都是**要填进输入框的完整字符串**，前端整段替换。 */
@Schema(description = "镜像候选值")
public record RegistrySuggestResponse(List<String> items) {
}
