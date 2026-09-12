package com.coding.platformapi.metrics.labels;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 节点磁盘 IO 按 {@code by(device)} 拆分：读设备名（如 sda / nvme0n1）作图例。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DeviceLabels extends MetricLabels {
    private String device;
}
