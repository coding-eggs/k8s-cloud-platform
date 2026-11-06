package com.coding.platformapi.configs;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/** 平台总览配置（{@code platform.overview.*}）。 */
@Data
@Configuration
@ConfigurationProperties(prefix = "platform.overview")
public class OverviewProperties {

    /** 服务名 → health URL；值为空 = 不探测、不计入分母。platform-api 自身不在此列，固定算 UP。 */
    private Map<String, String> healthTargets = new LinkedHashMap<>();
}
