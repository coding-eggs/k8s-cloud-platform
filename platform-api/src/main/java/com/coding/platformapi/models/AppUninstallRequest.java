package com.coding.platformapi.models;

import lombok.Data;

/** {@code POST /app/uninstall} 的请求体。 */
@Data
public class AppUninstallRequest {
    private String appId;
}
