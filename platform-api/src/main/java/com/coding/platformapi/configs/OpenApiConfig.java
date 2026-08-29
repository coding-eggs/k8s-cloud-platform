package com.coding.platformapi.configs;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public GroupedOpenApi platformApi() {

        return GroupedOpenApi.builder()
                .group("platform-api")
                .pathsToMatch("/**")
                .packagesToScan("com.coding.platformapi.controllers")
                .addOpenApiCustomizer(openApi -> {
                    openApi.getPaths().forEach((path, pathItem) -> {
                        pathItem.readOperations().forEach(operation -> {
                            String tag = (operation.getTags() != null && !operation.getTags().isEmpty())
                                    ? operation.getTags().getFirst()
                                    : "default";

                            String newId = tag + "_" + path.replace("/", "_");
                            operation.setOperationId(newId);
                        });
                    });
                })
                .build();
    }
}
