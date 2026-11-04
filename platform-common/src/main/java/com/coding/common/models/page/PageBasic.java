package com.coding.common.models.page;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 分页请求基类：需要分页的入参继承它（与 {@code BaseResources} 同一套「共享字段走基类」的约定）。
 *
 * <p>语义：<b>page 与 size 都给了才分页</b>；任一为空 = 返回全量。这样同一个端点既能被列表页
 * （带 page/size）用，也能被「按 id 找一条」这类要全量的调用方（不带 page/size）用 —— 前端不必为
 * 两种情况写两套解析。
 */
@Data
public class PageBasic {

    @Schema(description = "页码，从 1 开始；与 size 都不给 = 不分页")
    private Integer page;

    @Schema(description = "每页条数；与 page 都不给 = 不分页")
    private Integer size;

    /** 两者都给了才算分页 */
    public boolean paged() {
        return page != null && size != null;
    }
}
