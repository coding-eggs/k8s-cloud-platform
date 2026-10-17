package com.coding.k8sserver.components;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

import java.util.List;
import java.util.Map;

/**
 * 平台告警联动的两个对象（S3.6 批②）：固定名字、固定结构。
 *
 * <p>为什么由平台写而不是 helm values：token 是凭据，进 values 会落进操作记录快照。
 * 结构固定在这里，只有 webhook 地址随集群变化。
 *
 * <p>AlertmanagerConfig 必须在 **Alertmanager 自己的命名空间**（monitor）：
 * 它通过 Alertmanager 的 config secret 生成，跨命名空间的 AlertmanagerConfig 由
 * matcherStrategy 决定是否纳入。
 */
public final class PlatformAlertingObjects {

    public static final String SECRET_NAME = "platform-alert-token";
    public static final String SECRET_KEY = "token";
    public static final String CONFIG_NAME = "platform-webhook";

    public static final CustomResourceDefinitionContext ALERTMANAGER_CONFIG_CRD =
            new CustomResourceDefinitionContext.Builder()
                    .withGroup("monitoring.coreos.com")
                    .withVersion("v1alpha1")
                    .withKind("AlertmanagerConfig")
                    .withPlural("alertmanagerconfigs")
                    .withScope("Namespaced")
                    .build();

    private PlatformAlertingObjects() {
    }

    /** 共享密钥；token 已剥掉 Bearer 前缀（Alertmanager 会自己拼） */
    public static Secret secret(String namespace, String token) {
        return new SecretBuilder()
                .withNewMetadata()
                .withName(SECRET_NAME)
                .withNamespace(namespace)
                .addToLabels("platform.coding/managed", "true")
                .addToLabels("platform.coding/component", "prometheus-operator")
                .endMetadata()
                .withType("Opaque")
                .addToStringData(SECRET_KEY, token)
                .build();
    }

    /** 把所有告警（按 namespace + alertname 分组）转发到平台；resolved 也发 */
    public static GenericKubernetesResource alertmanagerConfig(String namespace, String webhookUrl) {
        Map<String, Object> spec = Map.of(
                "route", Map.of(
                        "receiver", "platform",
                        "groupBy", List.of("namespace", "alertname"),
                        "groupWait", "30s",
                        "groupInterval", "5m",
                        "repeatInterval", "12h"),
                "receivers", List.of(Map.of(
                        "name", "platform",
                        "webhookConfigs", List.of(Map.of(
                                "url", webhookUrl,
                                "sendResolved", true,
                                "timeout", "10s",
                                "httpConfig", Map.of(
                                        "authorization", Map.of(
                                                "type", "Bearer",
                                                "credentials", Map.of(
                                                        "name", SECRET_NAME,
                                                        "key", SECRET_KEY))))))));

        GenericKubernetesResource resource = new GenericKubernetesResource();
        resource.setApiVersion("monitoring.coreos.com/v1alpha1");
        resource.setKind("AlertmanagerConfig");
        resource.setMetadata(new ObjectMetaBuilder()
                .withName(CONFIG_NAME)
                .withNamespace(namespace)
                .addToLabels("platform.coding/managed", "true")
                .addToLabels("platform.coding/component", "prometheus-operator")
                .build());
        resource.setAdditionalProperty("spec", spec);
        return resource;
    }
}
