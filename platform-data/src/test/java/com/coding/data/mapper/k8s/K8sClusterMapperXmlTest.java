package com.coding.data.mapper.k8s;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * K8sClusterMapper.xml 是**手写的**，主键列最容易漏：{@code cluster_id} 是 varchar 主键，
 * 表上没有默认值，而 {@code useGeneratedKeys} 对非自增主键并不生效 —— 列一旦没写进 INSERT，
 * 运行期才会炸成 "Field 'cluster_id' doesn't have a default value"。
 * <p>
 * 这里钉住两条护栏：两条 INSERT 都必须带 {@code cluster_id}，且实体里的 id 是唯一的来源
 * （不允许留在 {@code useGeneratedKeys} 的幻想里）。
 */
class K8sClusterMapperXmlTest {

    private static String xml() throws Exception {
        try (var in = K8sClusterMapperXmlTest.class
                .getResourceAsStream("/mapper/k8s/K8sClusterMapper.xml")) {
            assertThat(in).as("mapper XML 必须在 classpath 上").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void insertWritesThePrimaryKeyColumn() throws Exception {
        String insert = between(xml(), "<insert id=\"insert\"", "</insert>");
        assertThat(insert).as("INSERT 必须显式写入 cluster_id（表上 NOT NULL 且无默认值）")
                .contains("cluster_id")
                .contains("#{clusterId");
    }

    @Test
    void insertSelectiveWritesThePrimaryKeyColumnWhenPresent() throws Exception {
        String selective = between(xml(), "<insert id=\"insertSelective\"", "</insert>");
        assertThat(selective).as("insertSelective 必须在 clusterId 非空时写入 cluster_id")
                .contains("cluster_id")
                .contains("#{clusterId");
    }

    @Test
    void insertsDoNotPretendTheKeyIsDatabaseGenerated() throws Exception {
        assertThat(xml()).as("cluster_id 由 ULIDGenerator 生成，不是自增列")
                .doesNotContain("useGeneratedKeys=\"true\"");
    }

    private static String between(String s, String from, String to) {
        int a = s.indexOf(from);
        int b = s.indexOf(to, a);
        assertThat(a).as("找不到 " + from).isGreaterThanOrEqualTo(0);
        assertThat(b).as("找不到 " + to).isGreaterThan(a);
        return s.substring(a, b);
    }
}
