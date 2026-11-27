package com.coding.k8score.converter.impl.autoscaling;

import com.coding.common.models.k8s.dto.HpaCrossVersionObjectReferenceDTO;
import com.coding.common.models.k8s.dto.HpaDTO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HpaV2ConverterTest {

    @Test
    void defaultsTheApiVersionForSupportedWorkloadsWhenOmitted() {
        assertThat(convertedVersion("Deployment", null)).isEqualTo("apps/v1");
        assertThat(convertedVersion("StatefulSet", "")).isEqualTo("apps/v1");
    }

    @Test
    void preservesExplicitVersionsAndDoesNotGuessForOtherTargets() {
        assertThat(convertedVersion("Deployment", "extensions/v1beta1")).isEqualTo("extensions/v1beta1");
        assertThat(convertedVersion("CustomWorkload", "example.io/v1")).isEqualTo("example.io/v1");
        assertThat(convertedVersion("CustomWorkload", null)).isNull();
    }

    private static String convertedVersion(String kind, String apiVersion) {
        HpaCrossVersionObjectReferenceDTO ref = new HpaCrossVersionObjectReferenceDTO();
        ref.setKind(kind);
        ref.setName("test-target");
        ref.setApiVersion(apiVersion);
        HpaDTO hpa = new HpaDTO();
        hpa.setScaleTargetRef(ref);
        return new HpaV2Converter().convert(hpa).getSpec().getScaleTargetRef().getApiVersion();
    }
}
