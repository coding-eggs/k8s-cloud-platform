package com.coding.platformapi.services.overview;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NamespaceCategoryTest {

    @Test
    void systemWinsOverAllocated() {
        assertThat(NamespaceCategory.of("kube-system", Set.of("kube-system"))).isEqualTo(NamespaceCategory.SYSTEM);
    }

    @Test
    void platformSystemAndKubePrefixAreSystem() {
        assertThat(NamespaceCategory.of("platform-system", Set.of())).isEqualTo(NamespaceCategory.SYSTEM);
        assertThat(NamespaceCategory.of("kube-public", Set.of())).isEqualTo(NamespaceCategory.SYSTEM);
        assertThat(NamespaceCategory.of("monitor", Set.of())).isEqualTo(NamespaceCategory.SYSTEM);
    }

    @Test
    void allocatedAndUnknown() {
        assertThat(NamespaceCategory.of("demo", Set.of("demo"))).isEqualTo(NamespaceCategory.ALLOCATED);
        assertThat(NamespaceCategory.of("x", Set.of("demo"))).isEqualTo(NamespaceCategory.UNALLOCATED);
    }
}
