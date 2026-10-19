package com.coding.platformapi.controllers;

import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.logs.LogEntryDTO;
import com.coding.platformapi.logs.LogQueryRequest;
import com.coding.platformapi.logs.LogQueryService;
import com.coding.platformapi.security.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 日志查询中心：查询 collector 写进 ES 的应用日志。
 *
 * <p>租户隔离：token 带租户帽时**强制**只看该租户分配的命名空间（忽略请求里的 tenantId）；
 * 平台管理员（base token）可以按租户筛选或不筛选（看全部）。
 */
@Tag(name = "日志查询", description = "按租户/集群/命名空间/工作负载/关键词/时间查应用日志")
@RestController
@RequestMapping("/logs")
@RequiredArgsConstructor
public class LogController {

    private final LogQueryService logs;
    private final AuthContext auth;

    @PostMapping("/search")
    @Operation(summary = "查询日志（ES 按天索引；search_after 翻页）",
            description = "缺省最近 1 小时；时间范围最多 31 天；limit ≤ 200")
    public ResponseData<List<LogEntryDTO>> search(@RequestBody LogQueryRequest body) {
        return new ResponseData<>(logs.search(body == null ? new LogQueryRequest() : body,
                auth.hatTenantId()));
    }
}
