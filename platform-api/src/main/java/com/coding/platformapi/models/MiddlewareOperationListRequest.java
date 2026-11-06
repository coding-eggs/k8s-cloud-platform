package com.coding.platformapi.models;

import com.coding.common.models.page.PageBasic;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 中间件实例操作记录分页查询入参（{@code /middleware/instance/operations}）。继承 {@link PageBasic}：
 * page/size 都给了才分页，都不给 = 返回全量。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MiddlewareOperationListRequest extends PageBasic {

    @Schema(description = "实例 id")
    private String instanceId;

    @Schema(description = "租户 id（仅管理员代管轨使用）")
    private String tenantId;
}
