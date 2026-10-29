package com.coding.common.models.middleware;

import lombok.Data;

/**
 * Elasticsearch 的一个索引（`_cat/indices` 的行）。
 *
 * <p>只做**只读**展示：平台不改索引、不删索引 —— 那些操作要求平台理解用户的 mapping 与生命周期
 * （ILM / 数据流），不属于纳管范围。
 */
@Data
public class EsIndexSummaryDTO {

    private String name;

    /** green / yellow / red */
    private String health;

    /** open / close */
    private String status;

    private Integer primaries;

    private Integer replicas;

    /** 文档数；关闭的索引读不到，为 null */
    private Long docs;

    /** 人类可读的存储占用（`_cat` 的原始写法，如 {@code 6.7mb}） */
    private String storeSize;

    /** 创建时间（`creation.date.string`，本地化字符串） */
    private String createdAt;
}
