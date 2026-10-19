package com.coding.platformapi.logs;

import lombok.Data;

import java.util.List;

/**
 * 日志查询条件（日志查询中心）。
 *
 * <p>数据源是 collector 写日志的那个 ES（{@code platform.logs.es.url}）。时间范围缺省最近 1 小时，
 * 最多 31 天（按天索引，范围过大等于一次扫几十个索引）。
 */
@Data
public class LogQueryRequest {

    /** 租户 id：平台管理员用它按租户筛选；租户用户会被后端强制成自己的租户 */
    private String tenantId;

    /** 集群名（日志里的 Resource.cluster.name） */
    private String clusterName;

    /** 命名空间（Resource.k8s.namespace.name） */
    private String namespace;

    /** 工作负载名（模糊匹配 service.name / deployment / statefulset / daemonset / pod） */
    private String workload;

    /** Pod 名（模糊匹配 Resource.k8s.pod.name） */
    private String pod;

    /** 全文关键词（Body 与 Attributes.*，AND 语义） */
    private String keyword;

    /** 级别：ERROR / WARN / INFO / DEBUG（精确匹配 SeverityText） */
    private String severity;

    /** 起始毫秒时间戳；空 = 最近 1 小时 */
    private Long startMillis;

    /** 结束毫秒时间戳；空 = 现在 */
    private Long endMillis;

    /** 默认 50，最大 200 */
    private Integer limit;

    /** 上一页最后一条的 sort 值（search_after 翻页游标）；首页不传 */
    private List<Object> searchAfter;
}
