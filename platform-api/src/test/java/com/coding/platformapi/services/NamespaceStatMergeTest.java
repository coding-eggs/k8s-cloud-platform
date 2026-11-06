package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.ClusterTotalDTO;
import com.coding.common.models.k8s.dto.NamespaceSnapshotDTO;
import com.coding.common.models.k8s.dto.NamespaceStatDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NamespaceStatMergeTest {

    private static NamespaceSnapshotDTO slice(int running, int pending, int succeeded) {
        NamespaceStatDTO stat = new NamespaceStatDTO();
        stat.setPodRunning(running);
        stat.setPodPending(pending);
        stat.setPodSucceeded(succeeded);
        NamespaceSnapshotDTO s = new NamespaceSnapshotDTO();
        s.setStat(stat);
        return s;
    }

    @Test
    void totalSumsPodBuckets() {
        ClusterTotalDTO t = NamespaceStatMerge.total(List.of(slice(2, 1, 0), slice(2, 0, 3)));

        assertThat(t.getPodRunning()).isEqualTo(4);
        assertThat(t.getPodPending()).isEqualTo(1);
        assertThat(t.getPodSucceeded()).isEqualTo(3);
    }
}
