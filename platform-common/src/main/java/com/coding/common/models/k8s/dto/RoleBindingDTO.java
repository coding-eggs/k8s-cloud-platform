package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "K8s RoleBinding 资源定义，将角色绑定到主体")
public class RoleBindingDTO extends BaseResources {

    @Schema(description = "引用的角色（Role 或 ClusterRole）", requiredMode = Schema.RequiredMode.REQUIRED)
    private RoleRefDTO roleRef;

    @Schema(description = "绑定主体列表（User、Group 或 ServiceAccount）", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<SubjectDTO> subjects;

    @Override
    public String getApiPath() {
        return "/resources/rolebindings";
    }

}