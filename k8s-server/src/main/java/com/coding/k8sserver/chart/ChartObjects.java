package com.coding.k8sserver.chart;

import com.coding.common.models.k8s.dto.ChartObjectDTO;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.util.ArrayList;
import java.util.List;

/** 把渲染清单解析成对象列表（只读展示；scope 判定不在这里）。 */
final class ChartObjects {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private ChartObjects() {
    }

    static List<ChartObjectDTO> scan(String manifest) {
        List<ChartObjectDTO> out = new ArrayList<>();
        if (manifest == null || manifest.isBlank()) {
            return out;
        }
        for (String doc : manifest.split("(?m)^---[ \t]*$")) {
            if (doc.isBlank()) {
                continue;
            }
            JsonNode root;
            try {
                root = YAML.readTree(doc);
            } catch (RuntimeException e) {
                continue; // 解析失败由 ManifestInspector 报 INVALID_OBJECT
            }
            if (root == null || root.isNull() || root.isMissingNode()) {
                continue;
            }
            ChartObjectDTO dto = new ChartObjectDTO();
            dto.setApiVersion(root.path("apiVersion").asString(""));
            dto.setKind(root.path("kind").asString(""));
            dto.setName(root.path("metadata").path("name").asString(""));
            dto.setNamespace(root.path("metadata").path("namespace").asString(""));
            out.add(dto);
        }
        return out;
    }
}
