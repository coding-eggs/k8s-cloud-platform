-- ============================================================================================
-- 镜像来源纳管（2026-10-28）
-- ============================================================================================
-- 1) 集群级镜像仓库覆盖：空 = 用平台默认（application.yaml 的 platform.image-registry.*）；
--    业务集群连不到平台 Harbor 时各自填自己的。
-- 2) 新端点 /cluster/components/images（镜像清单）的权限行 —— 表驱动授权下漏行会让
--    platform-api 启动期 PermissionCrossCheckRunner 直接 fail-closed。
--
-- 注意：MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

ALTER TABLE k8s_cluster
  ADD COLUMN image_registry_host    VARCHAR(255) NULL COMMENT '镜像仓库地址 host[:port]（空=平台默认）',
  ADD COLUMN image_registry_project VARCHAR(64)  NULL COMMENT '镜像仓库项目（空=平台默认 platform）';

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_res_cluster_components_images','API','/cluster/components/images','POST','platform:cluster:manage',
  '组件/中间件的镜像清单')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_cluster_components_images','builtin_role_admin','perm_res_cluster_components_images')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
