package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Data
@Schema(description = "入口灰度操作请求（开始 / 调整；全量、回退、指标只用 appId、serviceKey、note / window）")
public class AppCanaryRequest {

    @Schema(description = "应用 id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String appId;

    @Schema(description = "租户 id（管理员代操作时传；租户用户忽略）")
    private String tenantId;

    @Schema(description = "服务 key", requiredMode = Schema.RequiredMode.REQUIRED)
    private String serviceKey;

    @Schema(description = "灰度版本的完整期望配置；开始时不传 = 复制线上 stable；调整时不传 = 不改")
    private Map<String, Object> desired;

    @Schema(description = "灰度副本数（开始时默认 1；调整时不传 = 不改）")
    private Integer replicas;

    @Schema(description = "灰度权重 0-100（开始时默认 10；调整时不传 = 不改）")
    private Integer weight;

    @Schema(description = "是否开启 header 直达（仅 HTTP；开始时 HTTP 默认开，TCP 恒关；调整时不传 = 不改）")
    private Boolean headerEnabled;

    @Schema(description = "header 名（默认 x-canary）")
    private String headerName;

    @Schema(description = "header 值（默认 true）")
    private String headerValue;

    @Schema(description = "网关进来的流量是否参与灰度（仅 L7 网格且有网关暴露时可为 false；开始时默认 true，调整时不传 = 不改）")
    private Boolean external;

    @Schema(description = "发布备注；不填自动生成")
    private String note;

    @Schema(description = "指标时间窗：5m / 15m / 1h（仅 metrics 用）")
    private String window;
}
