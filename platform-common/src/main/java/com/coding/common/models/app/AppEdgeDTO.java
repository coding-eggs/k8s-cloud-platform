package com.coding.common.models.app;

import lombok.Data;

/** 图上的一条边，语义固定为「from 使用 to」：平台据此注入连接信息并推断部署顺序。 */
@Data
public class AppEdgeDTO {

    private String from;

    private String to;
}
