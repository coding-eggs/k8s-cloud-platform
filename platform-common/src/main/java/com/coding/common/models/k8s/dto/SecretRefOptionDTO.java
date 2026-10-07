package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.List;

/** Secret 下拉候选项：命名空间 + 名称 + data keys（不含数据值） */
@Data
public class SecretRefOptionDTO {

    private String namespace;

    private String name;

    /** data 的 key 列表（排序后） */
    private List<String> keys;

}
