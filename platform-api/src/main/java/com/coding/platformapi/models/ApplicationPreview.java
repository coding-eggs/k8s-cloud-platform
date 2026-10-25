package com.coding.platformapi.models;

import com.coding.common.models.app.AppProblemDTO;
import com.coding.common.models.app.AppObjectRefDTO;
import lombok.Data;

import java.util.List;

/** 部署前的预览：渲染结果 + 与上一版本的对象差异 + 图问题。 */
@Data
public class ApplicationPreview {

    private String manifest;

    private List<AppObjectRefDTO> objects;

    private List<AppProblemDTO> problems;

    /** 相对上一版本新增的对象（kind/name） */
    private List<String> added;

    /** 相对上一版本将被 prune 的对象（kind/name） */
    private List<String> removed;
}
