package com.coding.k8score.converter;

import com.coding.common.models.k8s.BaseResources;
import com.coding.common.models.k8s.dto.OwnerReferenceDTO;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.OwnerReference;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 {@code metadata} 里"判定受管资源"需要的那三样统一搬到 DTO：labels / annotations / ownerReferences。
 *
 * <p>为什么必须有 ownerReferences：Operator 给子资源（Pod/PVC/Service）打的是它自己的标签、
 * 甚至什么都不打（PVC 来自 volumeClaimTemplate，Helm 注解也不会继承），
 * 唯一可靠的线索是 ownerReferences 指向的属主名 —— 平台的实例名统一以 {@code mw-} 开头、
 * 组件 release 以 {@code platform-} 开头。不搬这一项，"中间件 PVC 不能删"就落不了地。
 */
public final class MetaUtil {

    private MetaUtil() {
    }

    /** labels/annotations/ownerReferences 一次性覆盖到 DTO。 */
    public static void copy(BaseResources dto, ObjectMeta meta) {
        if (dto == null || meta == null) {
            return;
        }
        dto.setLabels(meta.getLabels());
        dto.setAnnotations(annotations(meta));
        dto.setOwnerReferences(ownerRefs(meta));
    }

    /** fabric8 的注解是 {@code Map<String,String>}，DTO 侧是 {@code Map<String,Object>}：复制一份转换。 */
    public static Map<String, Object> annotations(ObjectMeta meta) {
        if (meta == null || meta.getAnnotations() == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>(meta.getAnnotations());
        return out;
    }

    public static List<OwnerReferenceDTO> ownerRefs(ObjectMeta meta) {
        if (meta == null || meta.getOwnerReferences() == null) {
            return null;
        }
        return meta.getOwnerReferences().stream().map(MetaUtil::toDto).toList();
    }

    private static OwnerReferenceDTO toDto(OwnerReference ref) {
        OwnerReferenceDTO dto = new OwnerReferenceDTO();
        dto.setApiVersion(ref.getApiVersion());
        dto.setKind(ref.getKind());
        dto.setName(ref.getName());
        dto.setController(ref.getController());
        return dto;
    }
}
