package com.coding.platformapi.services;

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
        validator.validate(dto);
        return k8s.update(dto);
    }
}
