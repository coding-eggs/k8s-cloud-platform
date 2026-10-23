package com.coding.common.models.middleware;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 中间件的一种实例形态（目录展示用）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MiddlewareModeDTO {

    private String mode;

    private String label;

    private int minReplicas;

    /** 非空 = 该形态节点数固定，表单只读（单节点/单实例形态） */
    private Integer fixedReplicas;

    /** 节点数在该形态下的含义（主分片数 / 数据节点数 / Broker 数 ...） */
    private String replicasLabel;

    private String help;
}
