package com.coding.common.models.app;

import lombok.Data;

/** k8s-server → platform-api：初始化 Job 的结果（含日志尾，失败时用户能看到原因）。 */
@Data
public class AppInitResultDTO {

    private boolean ok;

    private String message;

    private String logTail;
}
