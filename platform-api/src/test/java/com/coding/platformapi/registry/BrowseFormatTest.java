package com.coding.platformapi.registry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 浏览结果里 {@code details} 列的展示格式化。两档共用 ——
 * 列名两档一样（推送时间 / 大小 / digest），各写一份迟早会漂成同一个界面里两套写法。
 */
class BrowseFormatTest {

    @Test
    void formatsSizesForTheDetailsColumn() {
        assertThat(BrowseFormat.formatSize(0)).isEqualTo("—");
        assertThat(BrowseFormat.formatSize(-1)).isEqualTo("—");
        assertThat(BrowseFormat.formatSize(512)).isEqualTo("512 B");
        assertThat(BrowseFormat.formatSize(1024)).isEqualTo("1.0 KB");
        assertThat(BrowseFormat.formatSize(2048)).isEqualTo("2.0 KB");
        assertThat(BrowseFormat.formatSize(86_400_000L)).isEqualTo("82.4 MB");
    }
}
