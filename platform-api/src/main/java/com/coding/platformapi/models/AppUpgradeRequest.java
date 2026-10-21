package com.coding.platformapi.models;

import lombok.Data;

/** {@code POST /app/upgrade} 的请求体。 */
@Data
public class AppUpgradeRequest {
    private String appId;
    private String version;
    private String digest;
    private String values;
}
