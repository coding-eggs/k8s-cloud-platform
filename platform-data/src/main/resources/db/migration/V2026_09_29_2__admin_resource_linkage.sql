-- admin 补挂资源域端点行（V2026_09_29_2）。
-- 背景：ExemptPaths 收窄后 /resource/** 需 tenant:* 动词 code；内置关联里 admin 只有 platform:%
-- （V2026_09_24_2），V2026_09_29_1 只补了 tenant-admin/tenant-member —— admin 会失去全部资源页。
-- 幂等：显式 id + ODKU，同 V2026_09_29_1 范式。重放后需重新登录（token 闭包在签发时计算）。
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_admin_res_', p.id), 'builtin_role_admin', p.id
FROM platform_permission p
WHERE p.id LIKE 'perm_res_%' AND p.deleted_at IS NULL
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
