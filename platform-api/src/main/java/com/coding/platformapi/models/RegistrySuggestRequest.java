package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 补全请求：用户在输入框里已有的**整串**。 */
@Data
public class RegistrySuggestRequest {

    @Schema(description = "输入框当前内容，如 harbor.example.com/proj/ap")
    private String imagePrefix;
}
