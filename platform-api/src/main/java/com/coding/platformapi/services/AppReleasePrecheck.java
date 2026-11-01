package com.coding.platformapi.services;

import com.coding.data.models.system.PlatformAppExecution;

/** 发布前校验（引用可解析、中间件已 READY 等）。不通过时抛异常，执行进入 FAILED。 */
public interface AppReleasePrecheck {

    void check(PlatformAppExecution execution);
}
