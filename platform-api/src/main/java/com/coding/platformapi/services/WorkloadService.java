package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.WorkloadDTO;
import com.coding.platformapi.k8s.K8sResourceClient;
import com.coding.platformapi.services.validation.WorkloadValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**工作负载业务层：create/update 先跑 §5 硬约束，通过再透传 k8s-server（list/get/yaml/delete 无业务规则，直连 client） */
@Service
@RequiredArgsConstructor
public class WorkloadService {

    private final K8sResourceClient k8s;
    private final WorkloadValidator validator;

    public WorkloadDTO create(WorkloadDTO dto) {
        validator.validate(dto);
        return k8s.create(dto);
    }

    public WorkloadDTO update(WorkloadDTO dto) {
        if (dto.getPodTemplate() == null) graftExistingSpec(dto); // 仅伸缩快捷（无 Pod 模板）：先取现有规格，否则校验会以「缺少 Pod 模板」拒绝
        validator.validate(dto);
        return k8s.update(dto);
    }

    /**仅伸缩请求（列表页伸缩快捷：只带 kind/name/namespace/replicas）：取现有资源并把完整规格嫁接到请求上，
     * 保留请求的身份字段与所请求的 replicas；资源不存在则抛 not-found。serviceName 由 k8s-server 侧沿用 existing，无需嫁接 */
    private void graftExistingSpec(WorkloadDTO dto) {
        WorkloadDTO existing = k8s.get(dto);
        if (existing == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST, "工作负载不存在: " + dto.getName());
        }
        dto.setPodTemplate(existing.getPodTemplate());
        // strategy / volumeClaimTemplates 同样被校验读取，且 k8s-server 整体替换时缺了会丢：请求未带则沿用 existing
        if (dto.getStrategy() == null) dto.setStrategy(existing.getStrategy());
        if (dto.getVolumeClaimTemplates() == null) dto.setVolumeClaimTemplates(existing.getVolumeClaimTemplates());
    }
}
