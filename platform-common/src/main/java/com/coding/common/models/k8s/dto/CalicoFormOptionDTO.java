package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.List;

/**
 * BGP 编辑器下拉候选（/calico/form-options）：命名空间、工作负载。
 * <p>Secret 引用按所选命名空间另走 /calico/form-options/secrets 级联查询，不在此全量返回。
 * 只返回名字，不含任何 Secret 数据值；集群级、admin client。
 */
@Data
public class CalicoFormOptionDTO {

    /** 集群内全部命名空间名 */
    private List<String> namespaces;

    /** 集群内 Deployment / StatefulSet（namespace + kind + name） */
    private List<WorkloadOptionDTO> workloads;

}
