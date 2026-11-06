package com.coding.platformapi.models;

import com.coding.common.models.page.PageBasic;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 发布记录分页查询入参（{@code /application/release/list}）。继承 {@link PageBasic}：
 * page/size 都给了才分页，都不给 = 返回全量。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AppReleaseListRequest extends PageBasic {

    @Schema(description = "应用 id")
    private String appId;

    @Schema(description = "租户 id（平台视图代操作时传）")
    private String tenantId;
}
