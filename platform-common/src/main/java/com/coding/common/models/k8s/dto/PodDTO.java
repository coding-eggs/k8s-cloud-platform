package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * Pod DTO（只读列表 + 删除；Pod 不支持平台侧创建/更新）
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PodDTO extends BaseResources {

    /** Running / Pending / Succeeded / Failed / Unknown */
    private String phase;

    private String nodeName;

    private String podIp;

    /** 容器重启次数合计 */
    private Integer restarts;

    /** 容器名列表 */
    private List<String> containers;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/resources/pods";
    }

}
