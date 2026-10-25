package com.coding.common.models.app;

import lombok.Data;

/** 图或渲染阶段的校验结果。ERROR 阻断部署，WARN 只提示。 */
@Data
public class AppProblemDTO {

    private String severity;

    private String nodeKey;

    private String message;

    public static AppProblemDTO error(String nodeKey, String message) {
        AppProblemDTO p = new AppProblemDTO();
        p.setSeverity("ERROR");
        p.setNodeKey(nodeKey);
        p.setMessage(message);
        return p;
    }
}
