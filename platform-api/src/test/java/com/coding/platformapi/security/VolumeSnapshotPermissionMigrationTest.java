package com.coding.platformapi.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 卷快照迁移的内容检查：端点都有行、角色按档绑定、RBAC 模板只加 volumesnapshots。 */
class VolumeSnapshotPermissionMigrationTest {

    private static final String SQL = read();

    private static final Pattern BINDING =
            Pattern.compile("\\('\\w+',\\s*'(builtin_role_\\w+)',\\s*'(\\w+)'\\)");

    private static String read() {
        try {
            return Files.readString(Path.of("../platform-data/src/main/resources/db/migration/"
                    + "V2026_11_02_20__volume_snapshot.sql"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Set<String>> bindings() {
        Map<String, Set<String>> out = new HashMap<>();
        Matcher m = BINDING.matcher(SQL);
        while (m.find()) {
            out.computeIfAbsent(m.group(1), k -> new HashSet<>()).add(m.group(2));
        }
        return out;
    }

    @Test
    void everyEndpointHasARowWithoutResourcePrefix() {
        assertThat(SQL)
                .contains("'/volumesnapshots/capability',    'POST',   'tenant:volumesnapshot:read'")
                .contains("'/volumesnapshots/list',          'POST',   'tenant:volumesnapshot:read'")
                .contains("'/volumesnapshots/{name}',        'GET',    'tenant:volumesnapshot:read'")
                .contains("'/volumesnapshots/{name}/yaml',   'GET',    'tenant:volumesnapshot:read'")
                .contains("'/volumesnapshots',               'POST',   'tenant:volumesnapshot:create'")
                .contains("'/volumesnapshots/{name}',        'DELETE', 'tenant:volumesnapshot:delete'")
                .contains("'/volumesnapshotclasses/list',    'POST',   'platform:cluster:manage'")
                .contains("'/volumesnapshotclasses/{name}',  'GET',    'platform:cluster:manage'")
                .contains("'/volumesnapshotcontents/list',   'POST',   'platform:cluster:manage'")
                .contains("'/volumesnapshotcontents/{name}', 'GET',    'platform:cluster:manage'")
                .doesNotContain("/resource/volumesnapshot");
    }

    @Test
    void tenantMemberOnlyReads() {
        assertThat(bindings().get("builtin_role_tenant_member")).containsExactlyInAnyOrder(
                "perm_res_vs_capability", "perm_res_vs_list", "perm_res_vs_get", "perm_res_vs_yaml",
                "perm_page_volume_snapshots");
    }

    @Test
    void tenantAdminGetsTenantRowsButNoPlatformRows() {
        assertThat(bindings().get("builtin_role_tenant_admin")).containsExactlyInAnyOrder(
                "perm_res_vs_capability", "perm_res_vs_list", "perm_res_vs_get", "perm_res_vs_yaml",
                "perm_res_vs_create", "perm_res_vs_delete", "perm_page_volume_snapshots");
    }

    @Test
    void adminGetsEverything() {
        assertThat(bindings().get("builtin_role_admin")).containsExactlyInAnyOrder(
                "perm_res_vs_capability", "perm_res_vs_list", "perm_res_vs_get", "perm_res_vs_yaml",
                "perm_res_vs_create", "perm_res_vs_delete",
                "perm_res_vsclass_list", "perm_res_vsclass_get",
                "perm_res_vscontent_list", "perm_res_vscontent_get",
                "perm_page_volume_snapshots", "perm_page_volume_snapshot_cluster");
    }

    @Test
    void rbacTemplateAddsOnlyVolumeSnapshots() {
        assertThat(SQL).contains("JSON_ARRAY_APPEND(rules, '$.rules'")
                .contains("{\"apiGroups\":[\"snapshot.storage.k8s.io\"],\"resources\":[\"volumesnapshots\"],\"verbs\":[\"*\"]}")
                .contains("rules NOT LIKE '%snapshot.storage.k8s.io%'")
                .doesNotContain("\"volumesnapshotclasses\"")
                .doesNotContain("\"volumesnapshotcontents\"");
    }
}
