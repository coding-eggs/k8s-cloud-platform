package com.coding.common.models.page;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 分页结果包裹：所有走 PageHelper 的分页查询，返回都用它包一层（items + total）。
 *
 * <p>出参放在 {@code ResponseData<PageResult<T>>} 里 —— 前端拿到的 shape 是
 * {@code { items: [...], total: N }}。不分页的调用方（page/size 没给）也回这个形状，
 * 只是 total = 全量条数、items = 全部行，前端不用为两种情况写两套解析。
 */
public record PageResult<T>(
        @Schema(description = "当前页数据") List<T> items,
        @Schema(description = "总条数") long total
) {

    public static <T> PageResult<T> of(List<T> items, long total) {
        return new PageResult<>(items == null ? List.of() : items, total);
    }

    public static <T> PageResult<T> empty() {
        return new PageResult<>(List.of(), 0L);
    }
}
