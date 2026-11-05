package com.coding.common.audit;

/** 审计相关的跨服务请求头。 */
public final class AuditHeaders {

    /**
     * 网关转发时附的「外部路径」（前端看到的路径，去掉 nginx 的 /api、不含 query）。
     * 业务服务审计优先记它，让同一操作在网关 DENIED 与服务 SUCCESS/FAILED 里路径一致；
     * 网关对每个请求都覆写此头，客户端自带的值不会透传。
     */
    public static final String ORIGINAL_PATH = "X-Platform-Original-Path";

    private AuditHeaders() {}
}
