-- 用户平台角色回显端点权限行（V2026_10_01_1）。
-- 背景：UserView 对话框从盲操作改为回显，新增 POST /user/platformRole/list；
-- 表驱动鉴权下无行的端点会被启动交叉校验 fail-closed 拒启，故先于新构建重放本文件。
-- code 沿用 platform:user:manage（与 /user/* 其余行同码 ANY-of，见 V2026_09_24_2）。
-- 幂等：显式 id + ODKU，同 V2026_09_27_1 范式。
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_user_manage6','API','/user/platformRole/list','POST','platform:user:manage','查询用户已持平台角色（回显）')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);
