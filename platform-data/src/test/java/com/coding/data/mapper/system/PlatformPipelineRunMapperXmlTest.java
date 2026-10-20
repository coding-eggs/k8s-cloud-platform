package com.coding.data.mapper.system;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mapper XML 是**手写的**，加列最容易漏在这里：实体加了字段而 XML 没接，
 * 表现是「字段永远是 null」而不是报错。所以用一份资源级护栏钉住。
 */
class PlatformPipelineRunMapperXmlTest {

    private static final List<String> NEW_COLUMNS = List.of(
            "parent_run_id", "attempt_no", "retry_from_node",
            "masked_nodes_json", "reused_nodes_json", "workspace_pvc");

    private static String xml() throws Exception {
        try (var in = PlatformPipelineRunMapperXmlTest.class
                .getResourceAsStream("/mapper/system/PlatformPipelineRunMapper.xml")) {
            assertThat(in).as("mapper XML 必须在 classpath 上").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void everyNewColumnIsMappedInTheResultMap() throws Exception {
        String xml = xml();
        for (String col : NEW_COLUMNS) {
            assertThat(xml).as("resultMap 缺列 " + col).contains("column=\"" + col + "\"");
        }
    }

    @Test
    void everyNewColumnIsInserted() throws Exception {
        String insert = between(xml(), "<insert id=\"insert\"", "</insert>");
        for (String col : NEW_COLUMNS) {
            assertThat(insert).as("insert 缺列 " + col).contains(col);
        }
    }

    @Test
    void theListQueryCarriesTheAttemptColumnsForTheBadge() throws Exception {
        String list = between(xml(), "<sql id=\"List_Column_List\">", "</sql>");
        assertThat(list).contains("parent_run_id").contains("attempt_no");
        // 两个 JSON 大字段**不进列表**（几十条记录不该每条都背着）
        assertThat(list).doesNotContain("masked_nodes_json").doesNotContain("reused_nodes_json");
    }

    private static String between(String s, String from, String to) {
        int a = s.indexOf(from);
        int b = s.indexOf(to, a);
        assertThat(a).as("找不到 " + from).isGreaterThanOrEqualTo(0);
        assertThat(b).as("找不到 " + to).isGreaterThan(a);
        return s.substring(a, b);
    }
}
