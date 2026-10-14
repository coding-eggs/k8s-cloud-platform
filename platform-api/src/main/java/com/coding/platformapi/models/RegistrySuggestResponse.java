package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 补全结果。
 *
 * <p>每条都带**来源连接名**：一次输入会同时问多条连接（聚合，见
 * docs/superpowers/specs/2026-10-14-registry-dockerhub-design.md §2），
 * 而「这条候选是哪个仓库来的」是唯一无法从候选串里推出来的信息
 * （Harbor 档的候选串自带 host，但「是哪个连接」推不出来）。
 */
@Schema(description = "镜像候选值")
public record RegistrySuggestResponse(List<Item> items) {

    /**
     * @param value          要填进输入框的**完整字符串**，前端整段替换、不做任何拼接
     * @param connectionName 来源连接名，**只用于展示**。⚠️ 它**不是唯一键** ——
     *                       {@code platform_credential.name} 上有唯一约束，但前端不该依赖它做后续查询，
     *                       也不该只用它当 {@code v-for} 的 key（两条连接返回同一个 value 是可能的）
     */
    public record Item(String value, String connectionName) {
    }
}
