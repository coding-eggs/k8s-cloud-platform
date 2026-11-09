package com.coding.common.models.app;

import lombok.Data;

import java.util.List;

/**
 * platform-api → k8s-server：把跨命名空间依赖的凭据 Secret 对齐到应用所在的命名空间。
 *
 * <p>K8s 的 {@code secretKeyRef} 不能跨命名空间，所以实例不在应用命名空间时，平台把它的凭据
 * Secret 复制一份过去（名字由 platform-api 定，带依赖名前缀，避免不同 ns 同名的实例撞车）。
 * 每次发布 / 初始化前对齐一次；目标命名空间里由本应用复制、这次不在清单里的副本会被删掉，
 * 移除依赖或换绑实例后不留孤儿。只认本平台打的标签，用户自建的 Secret 不碰。
 */
@Data
public class AppDependencySecretRequestDTO {

    private String clusterId;

    /** 应用所在的命名空间（副本落在这里） */
    private String namespace;

    /** 应用 id：副本带这个标签，清理按它收口 */
    private String appId;

    /** 期望存在的副本；空列表 = 只做清理 */
    private List<Copy> copies;

    /** 一份副本：把 {@code sourceNamespace/sourceName} 复制成应用命名空间里的 {@code targetName}。 */
    @Data
    public static class Copy {

        private String sourceNamespace;

        private String sourceName;

        private String targetName;

        /**
         * 本次发布（或初始化）真的会用到这个依赖。false = 只是登记着的依赖，副本照建，
         * 但来源 Secret 暂时读不到时不挡发布（例如实例还在创建、凭据 Secret 尚未生成）。
         */
        private boolean required;

        public static Copy of(String sourceNamespace, String sourceName, String targetName, boolean required) {
            Copy c = new Copy();
            c.setSourceNamespace(sourceNamespace);
            c.setSourceName(sourceName);
            c.setTargetName(targetName);
            c.setRequired(required);
            return c;
        }
    }
}
