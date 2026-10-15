package com.coding.k8score.pipeline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 画布 JSON 本身的形状。
 *
 * <p>⚠️ 画布是**唯一真相源**（集群里不留 Pipeline 对象），所以它的字段只增不减：
 * 少一个字段 = 用户那次保存里的东西被静默丢掉。
 */
class PipelineCanvasTest {

    @Test
    void theLayoutCoordinatesSurviveARoundTrip() {
        // ⭐ 2026-10-15 用户报：保存前节点之间是有距离的，保存后再编辑就叠在一起了。
        //    原因就是坐标**从来没进过画布** —— 只存 id/type/name/config 与边，
        //    重开时按序号重排（80 + (i%3)*200），手拖的布局全丢。
        //    坐标是**纯展示**的东西，但它属于画布：不存下来就等于每次打开都换一张图。
        String json = """
                {"nodes":[{"id":"a","type":"git","name":"拉源码","config":{"repo":"r"},
                           "position":{"x":123,"y":45}}],"edges":[]}""";

        PipelineCanvas back = PipelineCanvas.fromJson(
                PipelineCanvas.toJson(PipelineCanvas.fromJson(json)));

        assertThat(back.nodes()).singleElement().satisfies(n -> {
            assertThat(n.position()).isNotNull();
            assertThat(n.position().x()).isEqualTo(123);
            assertThat(n.position().y()).isEqualTo(45);
            // 别的字段一个都不能丢
            assertThat(n.id()).isEqualTo("a");
            assertThat(n.type()).isEqualTo("git");
            assertThat(n.name()).isEqualTo("拉源码");
            assertThat(n.config()).containsEntry("repo", "r");
        });
    }

    @Test
    void anOldCanvasWithoutCoordinatesStillParses() {
        // 加这个字段之前存下来的画布没有 position —— 它们必须照常读得进来（前端会退回自动排布）
        PipelineCanvas c = PipelineCanvas.fromJson(
                """
                {"nodes":[{"id":"a","type":"git","config":{"repo":"r"}}],"edges":[]}""");

        assertThat(c.nodes()).singleElement()
                .satisfies(n -> assertThat(n.position()).isNull());
    }

    @Test
    void anUnknownFieldIsDroppedNotRejected() {
        // ⭐ 为什么值得钉住：画布的字段是**会长的**（刚加了 position），而前端与 k8s-server
        //    是两个构建。这边宽容，前端就能先上线 —— 旧后端只是把不认识的字段丢掉
        //    （代价：那时保存的布局会丢），而不是把「保存」整条拒掉、让用户以为流水线坏了。
        //    反过来（后端先上）天然没事：缺字段 = null。
        PipelineCanvas c = PipelineCanvas.fromJson(
                """
                {"nodes":[{"id":"a","type":"git","config":{},"futureField":{"x":1}}],"edges":[]}""");

        assertThat(c.nodes()).singleElement().satisfies(n -> {
            assertThat(n.id()).isEqualTo("a");
            assertThat(n.position()).isNull();
        });
    }
}
