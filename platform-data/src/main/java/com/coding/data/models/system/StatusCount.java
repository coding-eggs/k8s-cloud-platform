package com.coding.data.models.system;

import lombok.Data;

/** 概览计数：按某个状态 / 级别分组后的条数（status 可为 null，例如从未运行过的流水线）。 */
@Data
public class StatusCount {

    private String status;

    private long count;
}
