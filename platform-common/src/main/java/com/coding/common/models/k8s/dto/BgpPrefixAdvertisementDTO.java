package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.List;

/**
 * BGPConfiguration spec.prefixAdvertisements 单项（projectcalico.org/v3）：按前缀的宣告配置。
 */
@Data
public class BgpPrefixAdvertisementDTO {

    /** 目标 CIDR，如 {@code 10.48.0.0/16} */
    private String cidr;

    /** 附加的 community（communities 表中的名称或 {@code aa:nn}/{@code aa:nn:mm} 值） */
    private List<String> communities;

}
