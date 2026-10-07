package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.List;

/**
 * BGPFilter 规则操作单项（projectcalico.org/v3 {@code spec.*[].operations[]}）。
 * <p>真实 schema 是判别联合（每项恰设一个嵌套对象）；展示向扁平化为三个互斥字段：
 * addCommunity{value} → addCommunity、prependASPath{prefix[]} → prependAsPath、setPriority{value} → setPriority。
 */
@Data
public class BgpFilterOperationDTO {

    /** 附加的 community 值（{@code aa:nn} / {@code aa:nn:mm}） */
    private String addCommunity;

    /** 前置的 AS 号序列（结果 path 以该序列开头） */
    private List<String> prependAsPath;

    /** 设置的路由优先级（1..2147483646） */
    private Integer setPriority;

}
