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

    private String help;
}
