package com.coding.platformapi.models;

import lombok.Data;

import java.util.List;

/** 新建应用表单的可选项。 */
@Data
public class ApplicationOptionsView {

    /** 当前租户在该集群分到的命名空间 */
    private List<String> namespaces;
}
