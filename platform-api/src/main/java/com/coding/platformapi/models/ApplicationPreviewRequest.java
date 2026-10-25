package com.coding.platformapi.models;

import com.coding.common.models.app.AppSpecDTO;
import lombok.Data;

/** 预览入参：spec 可空（空 = 用台账里的当前 spec）。 */
@Data
public class ApplicationPreviewRequest {

    private String appId;

    private AppSpecDTO spec;

    private String tenantId;
}
