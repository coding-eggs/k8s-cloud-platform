package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.HpaDTO;
import com.coding.common.models.k8s.dto.HpaCrossVersionObjectReferenceDTO;
import com.coding.platformapi.k8s.K8sResourceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * HPA 业务层：create/update 前做「一负载一 HPA」硬校验（条目 3 后端半；前端禁选是 UX 半，双保险）。
 * list/get/yaml/delete 无业务规则，controller 直连 client。
 * scaleTargetRef.namespace 不经 converter round-trip，比较只按 kind+name（HPA 自身 ns 内，list 已按 ns 圈定）。
 */
@Service
@RequiredArgsConstructor
public class HpaService {

    private final K8sResourceClient k8s;

    public HpaDTO create(HpaDTO body) {
        validateSingleBinding(body, null);
        return k8s.create(body);
    }

    public HpaDTO update(HpaDTO body) {
        validateSingleBinding(body, body.getName());
        return k8s.update(body);
    }

    /** 目标 ref 缺失 → 放行（真非法由 apiserver 拒；本校验只管重复绑定） */
    private void validateSingleBinding(HpaDTO body, String excludeName) {
        HpaCrossVersionObjectReferenceDTO ref = body.getScaleTargetRef();
        if (ref == null || !StringUtils.hasText(ref.getName())) {
            return;
        }
        HpaDTO q = new HpaDTO();
        q.setTenantId(body.getTenantId());
        q.setClusterId(body.getClusterId());
        q.setNamespace(body.getNamespace());
        for (HpaDTO existing : k8s.list(q)) {
            HpaCrossVersionObjectReferenceDTO er = existing.getScaleTargetRef();
            if (er == null || !ref.getName().equals(er.getName())) {
                continue;
            }
            if (ref.getKind() == null ? er.getKind() == null : ref.getKind().equalsIgnoreCase(er.getKind())) {
                if (excludeName == null || !excludeName.equals(existing.getName())) {
                    throw new CloudPlatformException(EnumResponseType.HPA_TARGET_ALREADY_BOUND,
                            "工作负载「" + ref.getKind() + "/" + ref.getName() + "」已绑定 HPA「" + existing.getName()
                                    + "」，一个工作负载只能绑定一个 HPA");
                }
            }
        }
    }
}
