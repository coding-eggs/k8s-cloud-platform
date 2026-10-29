package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 连接列表的可选过滤。空 body / 不传 type = 全类型。 */
@Data
public class CredentialListRequest {

    @Schema(description = "GIT / HARBOR / DOCKERHUB；不传 = 全部")
    private String type;
}
