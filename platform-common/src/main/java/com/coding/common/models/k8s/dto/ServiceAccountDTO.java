package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "K8s ServiceAccount 资源定义，服务账户")
public class ServiceAccountDTO extends BaseResources {

    /**
     * 拉取镜像用的 Secret 列表（Pod 没写 {@code serviceAccountName} 时由它继承）。
     *
     * <p>平台只用它做一件事：把租户的 registry 凭据挂到命名空间的 {@code default} 上
     * （见 spec 2026-10-31 §6）。除此之外没有别处写这个字段。
     */
    @Schema(description = "imagePullSecrets（拉镜像用的 Secret 名列表）")
    private List<ImagePullSecretRefDTO> imagePullSecrets;

    @Override
    public String getApiPath() {
        return "/serviceaccounts";
    }

}