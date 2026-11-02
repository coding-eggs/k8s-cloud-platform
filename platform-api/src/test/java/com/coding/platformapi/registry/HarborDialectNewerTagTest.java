package com.coding.platformapi.registry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 新版本提示的判据：**按推送时间**，不按 tag 字面顺序（1.10 排在 1.9 前、latest 没有顺序可言）。
 */
class HarborDialectNewerTagTest {

    private static final CredentialRef CRED =
            new CredentialRef("c1", "h", "HARBOR", "harbor.x", "u", "p");

    private static String artifacts(String... rows) {
        return "[" + String.join(",", rows) + "]";
    }

    private static String art(String pushTime, String... tags) {
        StringBuilder t = new StringBuilder("[");
        for (int i = 0; i < tags.length; i++) {
            t.append(i == 0 ? "" : ",").append("{\"name\":\"").append(tags[i]).append("\"}");
        }
        t.append("]");
        return "{\"digest\":\"sha256:" + pushTime + "\",\"push_time\":\"" + pushTime + "\",\"tags\":" + t + "}";
    }

    @Test
    void newestPushIsOfferedEvenWhenItsTagSortsLower() {
        // 1.9.0 比 1.10.0 晚推送：字面顺序会选错
        var arts = HarborDialect.parsePushed(artifacts(
                art("2026-01-01T00:00:00.000Z", "1.8.0"),
                art("2026-02-01T00:00:00.000Z", "1.10.0"),
                art("2026-03-01T00:00:00.000Z", "1.9.0")));
        assertThat(HarborDialect.newerTagOf(arts, "1.10.0")).isEqualTo("1.9.0");
    }

    @Test
    void nothingNewerMeansNoHint() {
        var arts = HarborDialect.parsePushed(artifacts(
                art("2026-01-01T00:00:00.000Z", "1.8.0"),
                art("2026-02-01T00:00:00.000Z", "1.9.0")));
        assertThat(HarborDialect.newerTagOf(arts, "1.9.0")).isNull();
    }

    @Test
    void liveTagThatCannotBeFoundGivesNoHint() {
        // 线上 tag 已被删或不在最近一页：无法比较，宁可不提示
        var arts = HarborDialect.parsePushed(artifacts(art("2026-02-01T00:00:00.000Z", "1.9.0")));
        assertThat(HarborDialect.newerTagOf(arts, "0.1.0")).isNull();
    }

    @Test
    void aMovingLatestAliasIsNotOfferedWhenAConcreteTagSharesTheArtifact() {
        var arts = HarborDialect.parsePushed(artifacts(
                art("2026-01-01T00:00:00.000Z", "1.0.0"),
                art("2026-02-01T00:00:00.000Z", "latest", "1.1.0")));
        assertThat(HarborDialect.newerTagOf(arts, "1.0.0")).isEqualTo("1.1.0");
    }

    @Test
    void latestAloneIsOfferedBecauseItIsAllThereIs() {
        var arts = HarborDialect.parsePushed(artifacts(
                art("2026-01-01T00:00:00.000Z", "1.0.0"),
                art("2026-02-01T00:00:00.000Z", "latest")));
        assertThat(HarborDialect.newerTagOf(arts, "1.0.0")).isEqualTo("latest");
    }

    @Test
    void untaggedAndUnparseableRowsAreIgnored() {
        var arts = HarborDialect.parsePushed(artifacts(
                art("2026-01-01T00:00:00.000Z", "1.0.0"),
                art("2026-03-01T00:00:00.000Z"),                       // 无 tag
                "{\"push_time\":\"yesterday\",\"tags\":[{\"name\":\"x\"}]}",   // 时间坏了
                "{\"tags\":[{\"name\":\"y\"}]}"));                      // 没时间
        assertThat(HarborDialect.newerTagOf(arts, "1.0.0")).isNull();
    }

    @Test
    void garbageJsonIsEmpty() {
        assertThat(HarborDialect.parsePushed("not json")).isEmpty();
        assertThat(HarborDialect.newerTagOf(List.of(), "1.0.0")).isNull();
    }

    @Test
    void queriesNewestFirstAndSplitsProjectFromRepository() {
        RegistryHttpClient http = mock(RegistryHttpClient.class);
        HarborDialect d = new HarborDialect(http);
        when(http.get(eq(CRED), anyString())).thenReturn(artifacts(
                art("2026-01-01T00:00:00.000Z", "1.0.0"),
                art("2026-02-01T00:00:00.000Z", "1.1.0")));

        assertThat(d.newerTag(CRED, "proj/team/app", "1.0.0")).isEqualTo(Optional.of("1.1.0"));
        org.mockito.Mockito.verify(http).get(CRED,
                "https://harbor.x/api/v2.0/projects/proj/repositories/team%2Fapp/artifacts"
                        + "?page_size=100&sort=-push_time");
    }

    @Test
    void repositoryWithoutProjectIsNotAHarborPath() {
        HarborDialect d = new HarborDialect(mock(RegistryHttpClient.class));
        assertThat(d.newerTag(CRED, "app", "1.0.0")).isEmpty();
    }

    @Test
    void httpFailuresPropagateSoTheCallerCanTellFailureFromNothingNew() {
        RegistryHttpClient http = mock(RegistryHttpClient.class);
        when(http.get(eq(CRED), anyString())).thenThrow(new IllegalStateException("boom"));
        assertThatThrownBy(() -> new HarborDialect(http).newerTag(CRED, "proj/app", "1"))
                .isInstanceOf(IllegalStateException.class);
    }
}
