package com.coding.platformapi.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 镜像来源纳管（2026-10-28）的迁移护栏。
 *
 * <p>两类东西必须一起到位，少一个都会在部署时咬人：
 * <ul>
 *   <li>{@code k8s_cluster} 的两列 —— 没有它们，按集群覆盖 Harbor 地址无从谈起；</li>
 *   <li>新端点 {@code /cluster/components/images} 的权限行 —— 表驱动授权下漏行 =
 *       {@code PermissionCrossCheckRunner} 启动即失败（2026-10-26 已经踩过一次）。</li>
 * </ul>
 */
class ImageRegistryPermissionMigrationTest {

    private static final Path SQL = Path.of(
            "../platform-data/src/main/resources/db/migration/V2026_10_28_1__image_registry.sql");

    @Test
    void addsPerClusterRegistryColumns() throws Exception {
        String sql = Files.readString(SQL);

        assertThat(sql).contains("ALTER TABLE k8s_cluster")
                .contains("image_registry_host")
                .contains("image_registry_project");
    }

    @Test
    void imageListEndpointHasAPermissionRowBoundToAdmin() throws Exception {
        String sql = Files.readString(SQL);

        // 路径必须与 ClusterController 的 /cluster 前缀一致；码沿用组件族现有的 platform:cluster:manage
        assertThat(sql).contains("'/cluster/components/images'")
                .contains("'POST'")
                .contains("'platform:cluster:manage'")
                .contains("'builtin_role_admin'");
    }
}
