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

    /** ServiceAccount 名称（spec.serviceAccountName，缺省 default） */
    private String serviceAccountName;

    private String hostIp;

    /** 容器重启次数合计 */
    private Integer restarts;

    /** 容器名列表 */
    private List<String> containers;

    /** 容器详情（spec + status 合并，供详情页展示；常规容器在前、初始化容器在后） */
    private List<PodContainerDTO> containerDetails;

    /** 状态原因（非就绪/失败时的说明，来自 pod.status.conditions + 容器 waiting/terminated；健康时为空） */
    private String statusReason;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/resources/pods";
    }

}
