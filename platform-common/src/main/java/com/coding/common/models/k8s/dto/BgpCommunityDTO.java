package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * BGPConfiguration spec.communities 单项（projectcalico.org/v3）：community 名称↔值映射。
 */
@Data
public class BgpCommunityDTO {

    /** 任意名称（RFC1123），供 prefixAdvertisements 引用 */
    private String name;

    /** community 值：标准 {@code aa:nn} 或 large {@code aa:nn:mm} */
    private String value;

}
