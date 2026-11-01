package com.coding.platformapi.services;

import com.coding.common.models.app.AppRenderRequestDTO;
import com.coding.data.models.system.PlatformAppRelease;
import com.coding.data.models.system.PlatformApplication;

/** 把一次发布的快照翻译成渲染请求。快照格式归实现方，执行器不解析。 */
public interface AppReleaseRenderer {

    AppRenderRequestDTO build(PlatformApplication app, PlatformAppRelease release);
}
