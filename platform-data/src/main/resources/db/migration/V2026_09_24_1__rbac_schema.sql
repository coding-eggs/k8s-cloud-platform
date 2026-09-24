ALTER TABLE platform_role
  ADD COLUMN scope     varchar(16) NOT NULL DEFAULT 'PLATFORM' COMMENT '角色族：PLATFORM/TENANT',
  ADD COLUMN built_in  tinyint     NOT NULL DEFAULT 0  COMMENT '内置角色：不可删除、code/scope 不可改';
-- 既有内置 admin 角色标记为不可改
UPDATE platform_role SET scope='PLATFORM', built_in=1 WHERE code='admin';
