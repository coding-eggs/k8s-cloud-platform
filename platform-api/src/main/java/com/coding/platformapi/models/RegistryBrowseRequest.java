package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/** 浏览请求：路径为空 = 第一层。层级深度由方言决定，前端不认识层级名。 */
@Data
public class RegistryBrowseRequest {

    private String credentialId;

    /** 累积路径，如 ["myproj","team/app"]；空或 null = 第一层 */
    @Schema(description = "从根到当前层的路径")
    private List<String> path;
}
