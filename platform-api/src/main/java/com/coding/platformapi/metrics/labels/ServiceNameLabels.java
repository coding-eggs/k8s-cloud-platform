package com.coding.platformapi.metrics.labels;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** {@code sum by (destination_service_name)} 查询的标签：入口灰度按目标 Service 拆 stable / canary。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ServiceNameLabels extends MetricLabels {

    @JsonProperty("destination_service_name")
    private String serviceName;
}
