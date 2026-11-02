-- ============================================================================================
-- 清理：应用模块去画布后的旧模型遗留（2026-11-02）
-- ============================================================================================
-- 背景：V2026_10_25_7 的画布模型（spec_json 真相源 + revision 快照 + operation 阶段机）已被
--       服务 / 依赖 / 发布 / 执行（V2026_11_02_10）取代，代码里的旧实体、mapper、
--       「旧模型应用只读」守卫都已删除。这里删掉库里对应的列、表与权限行。
--
-- 保留：platform_application_resource（新发布也写它）、platform_application_reference
--       （新依赖也写它）、platform_middleware_instance.app_id / app_node_id（新依赖也用）。
--
-- ⚠️ 本仓没有 Flyway，手工重放；可重复执行。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- ⚠️ 前提：库里不能还有「部署过的旧画布应用」（未删除、spec_json 非空、current_revision > 0
--       或资源台账里有对象）。这类应用的资源台账与新发布同号，删掉 spec_json 后它们会被当成
--       新模型应用放行发布，旧对象从此无人认领。必须**先用上一版构建在页面上删掉它们**。
--       有残留时第 0 步会报错（表名即提示）；就算客户端出错后继续往下跑，第 2～4 步也全部跳过。
-- ⚠️ 从未部署过的旧应用直接转成新模型的空应用（DRAFT），画布留下的引用行与中间件归属一并解除。
-- ⚠️ 老迁移 V2026_10_25_7 保持原样不动，全新环境会先建后删，幂等无害。
-- ============================================================================================

-- ===== 0) 前提检查 =====
SET @has_spec := (SELECT COUNT(*) FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_application'
                    AND COLUMN_NAME = 'spec_json');
SET @q := IF(@has_spec = 0, 'SELECT 0 INTO @blocked',
  'SELECT COUNT(*) INTO @blocked FROM platform_application a
    WHERE a.deleted_at IS NULL AND a.spec_json IS NOT NULL AND a.spec_json <> ''''
      AND (a.current_revision > 0
           OR EXISTS (SELECT 1 FROM platform_application_resource r WHERE r.app_id = a.id))');
PREPARE st FROM @q; EXECUTE st; DEALLOCATE PREPARE st;

SET @q := IF(@blocked = 0, 'SELECT ''ok: 没有已部署的旧画布应用'' AS precheck',
  'SELECT * FROM `存在已部署的旧画布应用_请先用上一版构建删除后再重放本迁移`');
PREPARE st FROM @q; EXECUTE st; DEALLOCATE PREPARE st;

-- ===== 1) 权限行：这 5 个画布端点已不存在（先删绑定，再删权限行）=====
DELETE FROM platform_role_permission
WHERE permission_id IN (
  'perm_app_preview', 'perm_app_operation_get', 'perm_app_revision_list',
  'perm_app_update', 'perm_app_deploy');

DELETE FROM platform_permission
WHERE id IN (
  'perm_app_preview', 'perm_app_operation_get', 'perm_app_revision_list',
  'perm_app_update', 'perm_app_deploy');

-- ===== 2) 从未部署的旧应用转成新模型空应用 =====
SET @legacy := IF(@has_spec = 1 AND @blocked = 0, 1, 0);

-- 画布建的中间件实例：归属解除，回到中间件商店（新模型里它没有对应的依赖行）
SET @q := IF(@legacy = 1,
  'UPDATE platform_middleware_instance m
     JOIN platform_application a ON a.id = m.app_id
      SET m.app_id = NULL, m.app_node_id = NULL, m.updated_at = NOW()
    WHERE a.spec_json IS NOT NULL AND a.spec_json <> ''''',
  'SELECT ''skip: 中间件归属''');
PREPARE st FROM @q; EXECUTE st; DEALLOCATE PREPARE st;

SET @q := IF(@legacy = 1,
  'DELETE r FROM platform_application_reference r
     JOIN platform_application a ON a.id = r.app_id
    WHERE a.spec_json IS NOT NULL AND a.spec_json <> ''''',
  'SELECT ''skip: 引用行''');
PREPARE st FROM @q; EXECUTE st; DEALLOCATE PREPARE st;

SET @q := IF(@legacy = 1,
  'UPDATE platform_application
      SET `status` = ''DRAFT'', live_release_no = 0, updated_at = NOW()
    WHERE deleted_at IS NULL AND spec_json IS NOT NULL AND spec_json <> ''''',
  'SELECT ''skip: 应用状态''');
PREPARE st FROM @q; EXECUTE st; DEALLOCATE PREPARE st;

-- ===== 3) platform_application 的两列 =====
SET @q := IF(@legacy = 1, 'ALTER TABLE platform_application DROP COLUMN spec_json',
  'SELECT ''skip: spec_json''');
PREPARE st FROM @q; EXECUTE st; DEALLOCATE PREPARE st;

SET @has_rev := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_application'
                   AND COLUMN_NAME = 'current_revision');
SET @q := IF(@has_rev = 1 AND @blocked = 0, 'ALTER TABLE platform_application DROP COLUMN current_revision',
  'SELECT ''skip: current_revision''');
PREPARE st FROM @q; EXECUTE st; DEALLOCATE PREPARE st;

-- ===== 4) 两张表 =====
SET @q := IF(@blocked = 0, 'DROP TABLE IF EXISTS platform_application_revision', 'SELECT ''skip: revision''');
PREPARE st FROM @q; EXECUTE st; DEALLOCATE PREPARE st;

SET @q := IF(@blocked = 0, 'DROP TABLE IF EXISTS platform_application_operation', 'SELECT ''skip: operation''');
PREPARE st FROM @q; EXECUTE st; DEALLOCATE PREPARE st;
