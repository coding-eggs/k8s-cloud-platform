-- Task 1: platform_role 增列 scope/built_in + admin 内置标记。
-- 可重放：条件式 ADD COLUMN（information_schema 判列存在）。dump 已含两列的库导入后再跑本文件安全跳过。
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_role' AND COLUMN_NAME = 'scope');
SET @ddl := IF(@col = 0,
  'ALTER TABLE platform_role ADD COLUMN scope varchar(16) NOT NULL DEFAULT ''PLATFORM'' COMMENT ''角色族：PLATFORM/TENANT'', ADD COLUMN built_in tinyint NOT NULL DEFAULT 0 COMMENT ''内置角色：不可删除、code/scope 不可改''',
  'SET @rbac_v1_skip = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

-- 既有内置 admin 角色标记为不可改（幂等 UPDATE）
UPDATE platform_role SET scope='PLATFORM', built_in=1 WHERE code='admin';
