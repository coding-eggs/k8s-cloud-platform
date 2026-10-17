package com.coding.k8sserver.components;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Secret;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台告警联动的两个对象：名字固定、结构固定。
 * token 由 platform-api 剥掉 Bearer 前缀后传入（AlertmanagerConfig 的 authorization.type 会自己拼）。
 */
class PlatformAlertingObjectsTest {

    @Test
    void theSecretIsFixedNamedAndCarriesTheToken() {
        Secret secret = PlatformAlertingObjects.secret("monitor", "sd");

        assertThat(secret.getMetadata().getName()).isEqualTo("platform-alert-token");
        assertThat(secret.getMetadata().getNamespace()).isEqualTo("monitor");
        assertThat(secret.getMetadata().getLabels()).containsEntry("platform.coding/managed", "true");
        assertThat(secret.getStringData()).containsEntry("token", "sd");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theConfigRoutesResolvedAlertsToThePlatformWithBearerAuth() {
        GenericKubernetesResource amc = PlatformAlertingObjects.alertmanagerConfig("monitor",
                "http://10.0.0.5:8081/alert/webhook?clusterId=c1");

        assertThat(amc.getApiVersion()).isEqualTo("monitoring.coreos.com/v1alpha1");
        assertThat(amc.getKind()).isEqualTo("AlertmanagerConfig");
        assertThat(amc.getMetadata().getName()).isEqualTo("platform-webhook");
        assertThat(amc.getMetadata().getNamespace()).isEqualTo("monitor");

        Map<String, Object> spec = (Map<String, Object>) amc.getAdditionalProperties().get("spec");
        Map<String, Object> route = (Map<String, Object>) spec.get("route");
        assertThat(route).containsEntry("receiver", "platform")
                .containsEntry("groupBy", List.of("namespace", "alertname"));

        List<Map<String, Object>> receivers = (List<Map<String, Object>>) spec.get("receivers");
        Map<String, Object> webhook = (Map<String, Object>)
                ((List<?>) receivers.get(0).get("webhookConfigs")).get(0);
        assertThat(webhook)
                .containsEntry("url", "http://10.0.0.5:8081/alert/webhook?clusterId=c1")
                .containsEntry("sendResolved", true);

        Map<String, Object> auth = (Map<String, Object>)
                ((Map<String, Object>) webhook.get("httpConfig")).get("authorization");
        assertThat(auth).containsEntry("type", "Bearer");
        assertThat((Map<String, Object>) auth.get("credentials"))
                .containsEntry("name", "platform-alert-token")
                .containsEntry("key", "token");
    }
}
