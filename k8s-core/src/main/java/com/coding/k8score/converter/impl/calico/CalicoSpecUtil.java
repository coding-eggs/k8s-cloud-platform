package com.coding.k8score.converter.impl.calico;

import com.coding.common.models.k8s.dto.BgpSecretKeyRefDTO;
import com.coding.common.models.k8s.dto.ConditionDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * BGP* 只读 converter 的共享 spec-Map 解析小工具（同 {@link IppoolConverter} 的类型安全转换风格，
 * 三个 BGP converter 复用一份避免漂移）。spec 以 Map 结构读写（fabric8 通用 CRD API，无代码生成依赖）。
 */
final class CalicoSpecUtil {

    private CalicoSpecUtil() {
    }

    /** spec 子 Map（res/properties/spec 任一为 null → null）。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> specOf(GenericKubernetesResource res) {
        if (res == null || res.getAdditionalProperties() == null) {
            return null;
        }
        Object spec = res.getAdditionalProperties().get("spec");
        return spec instanceof Map ? (Map<String, Object>) spec : null;
    }

    /** 任意对象 → spec 子 Map（非 Map → null）。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> mapOf(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    /** 字符串化（numorstring 的数字/串两态都归一为 String；null → null）。 */
    static String asStr(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    static Boolean asBool(Object o) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s);
        }
        return null;
    }

    static Integer asInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static List<String> asStringList(Object o) {
        if (!(o instanceof List)) {
            return null;
        }
        List<Object> raw = (List<Object>) o;
        List<String> out = new ArrayList<>(raw.size());
        for (Object e : raw) {
            if (e != null) {
                out.add(String.valueOf(e));
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** {@code spec.*Password.secretKeyRef} → 引用（name/namespace/key；只存引用不存密文）。 */
    static BgpSecretKeyRefDTO secretRef(Map<String, Object> ref) {
        if (ref == null) {
            return null;
        }
        BgpSecretKeyRefDTO dto = new BgpSecretKeyRefDTO();
        dto.setName(asStr(ref.get("name")));
        dto.setNamespace(asStr(ref.get("namespace")));
        dto.setKey(asStr(ref.get("key")));
        return dto;
    }

    // ---------- 正向写入（convert/convertForUpdate 用；null/空白不写，交还 Calico 默认）----------

    static void putStr(Map<String, Object> m, String key, String val) {
        if (val != null && !val.isBlank()) {
            m.put(key, val);
        }
    }

    static void putInt(Map<String, Object> m, String key, Integer val) {
        if (val != null) {
            m.put(key, val);
        }
    }

    static void putBool(Map<String, Object> m, String key, Boolean val) {
        if (val != null) {
            m.put(key, val);
        }
    }

    static boolean notEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }

    /** status 子 Map（无 status → null）。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> statusOf(GenericKubernetesResource res) {
        if (res == null || res.getAdditionalProperties() == null) {
            return null;
        }
        Object status = res.getAdditionalProperties().get("status");
        return status instanceof Map ? (Map<String, Object>) status : null;
    }

    /** {@code status.conditions} → DTO 列表（缺席 / 非列表 → null；同 IppoolConverter 解析口径）。 */
    static List<ConditionDTO> conditions(Map<String, Object> status) {
        if (status == null || !(status.get("conditions") instanceof List)) {
            return null;
        }
        List<ConditionDTO> out = new ArrayList<>();
        for (Object o : (List<?>) status.get("conditions")) {
            Map<String, Object> m = mapOf(o);
            if (m == null) {
                continue;
            }
            ConditionDTO c = new ConditionDTO();
            c.setType(asStr(m.get("type")));
            c.setStatus(asStr(m.get("status")));
            c.setReason(asStr(m.get("reason")));
            c.setMessage(asStr(m.get("message")));
            c.setLastTransitionTime(asStr(m.get("lastTransitionTime")));
            out.add(c);
        }
        return out.isEmpty() ? null : out;
    }

}
