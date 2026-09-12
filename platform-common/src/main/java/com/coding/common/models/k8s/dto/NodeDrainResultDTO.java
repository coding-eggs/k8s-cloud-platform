package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Drain 结果：驱逐 / 跳过（DaemonSet/静态/mirror）/ 失败，均为 ns/name。 */
@Data
public class NodeDrainResultDTO {
    private List<String> evicted = new ArrayList<>();
    private List<String> skipped = new ArrayList<>();
    private List<String> errors = new ArrayList<>();
}
