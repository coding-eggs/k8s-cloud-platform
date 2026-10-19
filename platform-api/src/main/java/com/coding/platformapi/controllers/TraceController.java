package com.coding.platformapi.controllers;

import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.trace.TempoTraceClient;
import com.coding.platformapi.trace.TraceDetailDTO;
import com.coding.platformapi.trace.TraceSearchRequest;
import com.coding.platformapi.trace.TraceSummaryDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 链路追踪（平台自研视图的数据面）：查询管理集群的 Tempo。
 *
 * <p>数据源是中心 Tempo（业务集群的 collector 回传到这里）；查询按 {@code cluster.name} /
 * {@code service.name} 过滤。页面与接口都只绑定平台管理员（见 V2026_10_19_2）。
 */
@Tag(name = "链路追踪", description = "查询 Tempo 的 trace 列表与详情（平台管理员）")
@RestController
@RequestMapping("/traces")
@RequiredArgsConstructor
public class TraceController {

    private final TempoTraceClient tempo;

    @PostMapping("/search")
    @Operation(summary = "按集群/服务/时间查 trace 列表",
            description = "TraceQL 过滤 resource.cluster.name / resource.service.name；缺省最近 1 小时，limit ≤ 200")
    public ResponseData<List<TraceSummaryDTO>> search(@RequestBody(required = false) TraceSearchRequest body) {
        return new ResponseData<>(tempo.search(body == null ? new TraceSearchRequest() : body));
    }

    @GetMapping("/services")
    @Operation(summary = "某集群下出现过的服务名（Tempo 标签值，供页面下拉）",
            description = "service.name 是 OTel 的服务名（平台默认写工作负载名），不是 K8s Service 对象")
    public ResponseData<List<String>> services(@RequestParam(required = false) String clusterName,
                                               @RequestParam(required = false) Long start,
                                               @RequestParam(required = false) Long end) {
        return new ResponseData<>(tempo.serviceNames(clusterName, start, end));
    }

    @GetMapping("/{traceId}")
    @Operation(summary = "查看一条 trace 的完整调用链（span 列表按开始时间排序）")
    public ResponseData<TraceDetailDTO> get(@PathVariable String traceId) {
        return new ResponseData<>(tempo.get(traceId));
    }
}
