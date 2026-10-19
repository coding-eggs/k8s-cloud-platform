package com.coding.platformapi.models;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 编辑表单的预填约定：没有历史值时 {@code currentValue} 必须**不出现在 JSON 里**。
 * <p>
 * 前端用的是「字段存在就用、否则退回 defaultValue」；如果后端序列化成 {@code "currentValue": null}，
 * 前端会把它当成有效值，输入框就是空的（metrics-server 的副本数踩过）。
 */
class EditableFieldDTOTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void aNullCurrentValueIsOmittedSoTheFormFallsBackToTheDefault() {
        EditableFieldDTO field = new EditableFieldDTO();
        field.setKey("replicas");
        field.setLabel("副本数");
        field.setType("number");
        field.setDefaultValue(1);

        String json = JSON.writeValueAsString(field);

        assertThat(json).doesNotContain("currentValue")
                .contains("\"defaultValue\":1");
    }

    @Test
    void aPresentCurrentValueIsSerialized() {
        EditableFieldDTO field = new EditableFieldDTO();
        field.setKey("replicas");
        field.setDefaultValue(1);
        field.setCurrentValue(3);

        assertThat(JSON.writeValueAsString(field)).contains("\"currentValue\":3");
    }
}
