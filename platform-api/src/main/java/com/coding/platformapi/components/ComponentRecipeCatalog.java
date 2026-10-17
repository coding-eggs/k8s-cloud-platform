package com.coding.platformapi.components;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 组件的安装方式目录（S3.6，批① 只接 metrics-server）。
 *
 * <p>与 {@link ClusterComponentCatalog} 的分工：那边回答"装没装"（探测判据），
 * 这边回答"怎么装"（chart / 版本 / values 契约）。两者用同一个 component code 关联。
 *
 * <p>批② 在这里逐条补其余 10 个组件；每个组件的契约值以 spec §5 为准。
 *
 * <p>⚠️ 同一组件的多个 chart 版本按**新 → 旧**排列：前端升级弹窗直接按这个顺序展示并默认选第一个。
 */
@Component
public class ComponentRecipeCatalog {

    private static final String BUSINESS = "BUSINESS";
    private static final String MANAGEMENT = "MANAGEMENT";

    private static final ComponentRecipe METRICS_SERVER = new ComponentRecipe(
            BUSINESS,
            List.of(new ReleaseSpec(
                    // chart 版本必须与 k8s-server/src/main/resources/charts/components/metrics-server/<version> 一致（官方版，不自定义）
                    "platform-metrics-server", "kube-system", "metrics-server", "3.14.0",
                    // 🔒 契约值
                    //  - args：自签 kubelet 证书的集群不加这个参数，metrics-server 永远 NotReady
                    //  - fullnameOverride：官方 chart 默认用 release 名做 fullname（= platform-metrics-server），
                    //    而组件探测认的是 deployment `metrics-server` —— 固定名字，探测与安装才对得上
                    Map.of("args", List.of("--kubelet-insecure-tls"),
                            "fullnameOverride", "metrics-server"),
                    // ⚙️ 可编辑默认值
                    Map.of("replicas", 1))),
            List.of());

    private static final Map<String, Map<String, ComponentRecipe>> RECIPES = Map.of(
            "metrics-server", Map.of(
                    BUSINESS, METRICS_SERVER,
                    MANAGEMENT, new ComponentRecipe(MANAGEMENT, METRICS_SERVER.releases(), List.of())));

    /** 没有安装方式的组件（批② 之前）返回 null；调用方据此禁用安装按钮 */
    public ComponentRecipe recipeOf(String code, String profile) {
        if (code == null || profile == null) {
            return null; // Map.of(...).get(null) 会 NPE
        }
        Map<String, ComponentRecipe> byProfile = RECIPES.get(code);
        return byProfile == null ? null : byProfile.get(profile);
    }

    public boolean supports(String code) {
        return RECIPES.containsKey(code);
    }
}
