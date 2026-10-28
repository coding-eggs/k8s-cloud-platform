package com.coding.platformapi.images;

/** 组件/中间件表单里的「镜像来源」。默认 {@link #PLATFORM}，可切 {@link #OFFICIAL} 应急。 */
public enum ImageSource {
    /** 平台 Harbor（platform 项目） */
    PLATFORM,
    /** 上游官方仓库（与纳管之前的行为完全一致） */
    OFFICIAL;

    public static ImageSource of(String value) {
        if (value == null || value.isBlank()) {
            return PLATFORM;
        }
        return "OFFICIAL".equalsIgnoreCase(value.trim()) ? OFFICIAL : PLATFORM;
    }
}
