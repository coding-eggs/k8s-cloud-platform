package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "K8s ServiceAccount 资源定义，服务账户")
public class ServiceAccountDTO extends BaseResources {

    @Override
    public String getApiPath() {
        return "/resources/serviceaccounts";
    }

}