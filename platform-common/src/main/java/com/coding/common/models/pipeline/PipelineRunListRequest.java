package com.coding.common.models.pipeline;

import com.coding.common.models.page.PageBasic;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 运行历史分页查询入参（{@code /pipeline/run/list}）。继承 {@link PageBasic}：
 * page/size 都给了才分页，都不给 = 返回全量（详情页「按 id 找 parent 名」那条路依赖全量）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PipelineRunListRequest extends PageBasic {

    @Schema(description = "流水线 id")
    private String id;
}
