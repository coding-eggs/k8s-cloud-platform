package com.coding.platformapi.models;

import com.coding.common.models.app.AppSpecDTO;
import lombok.Data;

/** 编辑应用（改图/改名）；部署进行中会被拒绝。 */
@Data
public class ApplicationUpdateRequest {

    private String appId;

    private String name;

    private String displayName;

    private String description;

    private AppSpecDTO spec;
}
