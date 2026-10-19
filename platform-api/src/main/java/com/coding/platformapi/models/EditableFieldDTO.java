package com.coding.platformapi.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 组件的一个**可编辑**配置项（⚙️，来自 recipe 的 editableValues）。
 *
 * <p>与契约值（🔒）的分界：契约值是平台功能正确性的前提，UI 不暴露、不可覆盖；
 * 这里只列 recipe 明确声明的少量字段。
 *
 * <p>{@code key} 是路径形式（如 {@code prometheus.prometheusSpec.retention}），
 * apply 时原样回传在 {@code editableValues} 里。
 */
@Data
public class EditableFieldDTO {
    /** 路径形式的 key（真正下发给 values 的路径） */
    private String key;
    /** 展示文案 */
    private String label;
    /** string / number / boolean / json */
    private String type;
    /** recipe 里的默认值 */
    private Object defaultValue;
    /**
     * **当前生效**的值（上次成功操作的 values 快照里按 {@code key} 取出来的）。
     * 编辑/升级表单用它预填，否则用户改完再打开看到的还是默认值，以为没生效；
     * 没有安装记录 → **字段不出现**（表单退回 {@code defaultValue}）。
     * <p>
     * ⚠️ 必须是 NON_NULL：序列化成 {@code "currentValue": null} 时，前端的
     * 「有值就用、否则用默认值」判断会把 null 当成有效值，输入框显示为空。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Object currentValue;
}
