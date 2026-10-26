package com.coding.platformapi.registry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HarborNamingTest {

    @Test
    void tenantProjectIsPrefixedWithT() {
        assertThat(HarborNaming.projectName("acme")).isEqualTo("t-acme");
        assertThat(HarborNaming.PLATFORM_PROJECT).isEqualTo("platform");
    }

    @Test
    void credentialNameCarriesTheClusterToAvoidCrossClusterCollisions() {
        assertThat(HarborNaming.credentialName("t-acme", "01HXABCDEF"))
                .isEqualTo("Harbor t-acme@01HXABCDEF");
    }

    @Test
    void longProjectNamesAreClippedButStayUnique() {
        String project = "t-" + "a".repeat(80);
        String a = HarborNaming.credentialName(project, "01HXABCDEF");
        String b = HarborNaming.credentialName(project + "b", "01HXABCDEF");
        assertThat(a).hasSizeLessThanOrEqualTo(64);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void robotNameInHarborFollowsThePrefixAndPlusConvention() {
        assertThat(HarborNaming.robotNameInHarbor("t-acme")).isEqualTo("robot$t-acme+ci");
    }
}
