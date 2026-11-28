package com.coding.platformapi.services.overview;

import com.coding.platformapi.configs.NamespaceProtectionProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NamespaceCategoryTest {

    private final NamespaceProtectionProperties protection = new NamespaceProtectionProperties();

    @Test
    void kubePrefixAloneDoesNotMakeNamespaceProtected() {
        protection.setProtectedNamespaces(List.of("kube-system"));
        assertThat(NamespaceCategory.of("kube-custom", Set.of(), protection)).isEqualTo(NamespaceCategory.UNALLOCATED);
    }

    @Test
    void systemWinsOverAllocated() {
        protection.setProtectedNamespaces(List.of("ops-shared"));
        assertThat(NamespaceCategory.of("ops-shared", Set.of("ops-shared"), protection)).isEqualTo(NamespaceCategory.SYSTEM);
    }

    @Test
    void emptyProtectionListDoesNotClassifyPresetNamesAsSystem() {
        assertThat(NamespaceCategory.of("platform-system", Set.of(), protection)).isEqualTo(NamespaceCategory.UNALLOCATED);
        assertThat(NamespaceCategory.of("monitor", Set.of("monitor"), protection)).isEqualTo(NamespaceCategory.ALLOCATED);
    }

    @Test
    void allocatedAndUnknown() {
        assertThat(NamespaceCategory.of("demo", Set.of("demo"), protection)).isEqualTo(NamespaceCategory.ALLOCATED);
        assertThat(NamespaceCategory.of("x", Set.of("demo"), protection)).isEqualTo(NamespaceCategory.UNALLOCATED);
    }
}
