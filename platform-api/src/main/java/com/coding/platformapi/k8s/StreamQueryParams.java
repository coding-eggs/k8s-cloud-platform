package com.coding.platformapi.k8s;

import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 流式日志端点的 query 参数组装（Pod 详情与节点视角共用）：container 空判 +
 * {@code sinceTime > tailLines > sinceSeconds} 三选一规则。
 * <p>
 * 规则放这里而不是 controller：它是"请求参数 → k8s-server wire 形态"的映射，属传输层；
 * controller 只负责把 {@code HttpServletResponse} 的输出流传进来。
 */
final class StreamQueryParams {

    private StreamQueryParams() {}

    static void putLogOptions(Map<String, String> params, String container,
                              Integer sinceSeconds, String sinceTime, Integer tailLines) {
        if (StringUtils.hasText(container)) {
            params.put("container", container);
        }
        // sinceTime（增量轮询，取上次之后所有新行，不丢行）> tailLines（初始化回看最近 N 行，安静容器也不空）
        // > sinceSeconds（旧时间窗，保留兼容）；都不给 = 全量
        if (sinceTime != null) {
            params.put("sinceTime", sinceTime);
        } else if (tailLines != null) {
            params.put("tailLines", String.valueOf(tailLines));
        } else if (sinceSeconds != null) {
            params.put("sinceSeconds", String.valueOf(sinceSeconds));
        }
    }
}
