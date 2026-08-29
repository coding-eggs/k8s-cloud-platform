package com.coding.k8sserver.configs;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public GroupedOpenApi k8sApi() {
        
        return GroupedOpenApi.builder()
                .group("k8s-server")
                .pathsToMatch("/**")
                .packagesToScan("com.coding.k8sserver.controllers")
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
