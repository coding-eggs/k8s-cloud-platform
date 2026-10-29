package com.coding.common.models.middleware;

import lombok.Data;

/**
 * 一个索引的结构：mapping / settings / aliases。
 *
 * <p>三段都是**已格式化的 JSON 文本**，不是 Map：mapping 与 settings 的键是用户自定的
 * （字段名、analyzer 名），建模成 DTO 只会得到一堆 {@code Map<String, Object>}；
 * 原样转文本还能让前端用同一个只读块渲染，不必为它再写一套 JSON 查看器。
 *
 * <p>别名里的 {@code filter} 一并返回 —— 那是「这个别名看得见哪些文档」的定义，
 * 查索引结构的人十次有九次是在找它。
 */
@Data
public class EsIndexDetailDTO {

    private String name;

    private String mapping;

    private String settings;

    private String aliases;
}
