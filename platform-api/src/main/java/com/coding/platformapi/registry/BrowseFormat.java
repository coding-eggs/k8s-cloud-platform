package com.coding.platformapi.registry;

/**
 * 浏览结果里 {@code details} 列的展示格式化。两档共用。
 *
 * <p>抽出来不是为了抽象，是为了**同一个界面上只有一套写法** —— 列名两档是一样的
 * （推送时间 / 大小 / digest），各写一份迟早会漂。
 */
public final class BrowseFormat {

    private BrowseFormat() {
    }

    /** 字节数转人类可读。{@code <= 0} 给 {@code —}（不造一个假的 0 B）。 */
    public static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "—";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        return String.format("%.1f MB", kb / 1024.0);
    }

    /** 一个 tag 行（响应映射的中间形态，便于单测）。 */
    public record TagInfo(String name, String pushedAt, long size, String digest) {
    }
}
