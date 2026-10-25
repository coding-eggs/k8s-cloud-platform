package com.coding.platformapi.models;

import lombok.Data;

import java.util.List;
import java.util.Map;

/** 画布左栏与新建表单的可选项。 */
@Data
public class ApplicationOptionsView {

    /** [{kind, name, description}] */
    private List<Map<String, Object>> nodeKinds;

    private List<String> namespaces;
}
