-- ============================================================================================
-- 对象存储（SeaweedFS 集群组件）· 桶 / 凭据 / 配额 台账 + 权限
-- ============================================================================================
-- 背景：对象存储以「集群级一套 S3 + 按桶分配」的方式接入（charts/components/seaweedfs）。
--       本迁移只加台账与权限；S3 的 identities 配置由平台接管（Secret
--       platform-seaweedfs-s3-config），租户的 AK/SK 是「每桶一组、按桶授权」。
--
-- 口径：
--   - 建桶 / 发凭据 / 设配额 = 管理员（platform:object-storage:manage）；
--   - 看桶 / 看对象 / 取自己的凭据 = 租户可读（tenant:object-storage:read）；
--   - 对象级写操作 v1 不做。
--
-- ⚠️ 本仓没有 Flyway，手工重放；重放后**不需要**重下发租户 RBAC 模板（平台侧权限）。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

CREATE TABLE IF NOT EXISTS platform_object_bucket (
  id           varchar(64)  NOT NULL,
  cluster_id   varchar(64)  NOT NULL,
  bucket       varchar(63)  NOT NULL COMMENT 'S3 桶名（平台生成 t-<租户短码>-<名字>）',
  tenant_id    varchar(64)  NOT NULL COMMENT '归属租户',
  display_name varchar(128) NULL,
  status       varchar(16)  NOT NULL COMMENT 'ACTIVE / DELETING / DELETED',
  created_by   varchar(128) NULL,
  created_at   datetime     NOT NULL,
  updated_at   datetime     NOT NULL,
  deleted_at   datetime     NULL,
  -- 软删后同名桶可以重建：只有未删除的行参与唯一约束
  active_key   varchar(140) GENERATED ALWAYS AS
               (IF(deleted_at IS NULL, CONCAT(cluster_id,'|',bucket), NULL)) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pob_active (active_key),
  KEY idx_pob_tenant (cluster_id, tenant_id, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对象存储桶台账';

CREATE TABLE IF NOT EXISTS platform_object_credential (
  id            varchar(64)  NOT NULL,
  cluster_id    varchar(64)  NOT NULL,
  bucket_id     varchar(64)  NOT NULL COMMENT 'platform_object_bucket.id',
  tenant_id     varchar(64)  NOT NULL,
  identity_name varchar(128) NOT NULL COMMENT 'SeaweedFS S3 配置里的 identity 名',
  access_key    varchar(128) NOT NULL COMMENT '可展示；secretKey 不落库',
  secret_name   varchar(128) NOT NULL COMMENT 'K8s Secret 名（platform-storage），存 access_key/secret_key',
  status        varchar(16)  NOT NULL COMMENT 'ACTIVE / REVOKED',
  created_by    varchar(128) NULL,
  created_at    datetime     NOT NULL,
  revoked_at    datetime     NULL,
  PRIMARY KEY (id),
  KEY idx_poc_bucket (bucket_id, status),
  KEY idx_poc_tenant (cluster_id, tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对象存储访问凭据台账（密钥本体在 K8s Secret）';

CREATE TABLE IF NOT EXISTS platform_object_quota (
  cluster_id  varchar(64) NOT NULL,
  tenant_id   varchar(64) NOT NULL,
  max_buckets int         NOT NULL COMMENT '该租户在本集群可拥有的桶数上限',
  updated_by  varchar(128) NULL,
  updated_at  datetime    NOT NULL,
  PRIMARY KEY (cluster_id, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对象存储按租户的桶配额';

-- ===== 权限行 =====
INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_page_object_storage',      'Page','/object-storage',                   'view','tenant:page:object-storage',   '对象存储页面可见性'),
 ('perm_os_buckets_list',          'API', '/object-storage/buckets/list',      'POST','tenant:object-storage:read',   '对象存储桶列表'),
 ('perm_os_quota_get',             'API', '/object-storage/quota/get',         'POST','tenant:object-storage:read',   '对象存储桶配额（读）'),
 ('perm_os_credential_get',        'API', '/object-storage/credentials/get',   'POST','tenant:object-storage:read',   '查看凭据（AK/SK）'),
 ('perm_os_objects_list',          'API', '/object-storage/objects/list',      'POST','tenant:object-storage:read',   '列对象'),
 ('perm_os_objects_stat',          'API', '/object-storage/objects/stat',      'POST','tenant:object-storage:read',   '对象详情'),
 ('perm_os_objects_download',      'API', '/object-storage/objects/download',  'POST','tenant:object-storage:read',   '下载对象'),
 ('perm_os_bucket_create',         'API', '/object-storage/buckets/create',    'POST','platform:object-storage:manage','创建桶（管理员代建）'),
 ('perm_os_bucket_delete',         'API', '/object-storage/buckets/delete',    'POST','platform:object-storage:manage','删除桶'),
 ('perm_os_quota_set',             'API', '/object-storage/quota/set',         'POST','platform:object-storage:manage','设置租户桶配额'),
 ('perm_os_credential_issue',      'API', '/object-storage/credentials/issue', 'POST','platform:object-storage:manage','发放凭据'),
 ('perm_os_credential_revoke',     'API', '/object-storage/credentials/revoke','POST','platform:object-storage:manage','吊销凭据')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

-- ===== 角色绑定（显式，不继承）=====
INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_page_object_storage',   'builtin_role_admin','perm_page_object_storage'),
 ('rp_tadmin_page_object_storage',  'builtin_role_tenant_admin','perm_page_object_storage'),
 ('rp_tmember_page_object_storage', 'builtin_role_tenant_member','perm_page_object_storage'),

 ('rp_admin_os_buckets_list',       'builtin_role_admin','perm_os_buckets_list'),
 ('rp_tadmin_os_buckets_list',      'builtin_role_tenant_admin','perm_os_buckets_list'),
 ('rp_tmember_os_buckets_list',     'builtin_role_tenant_member','perm_os_buckets_list'),

 ('rp_admin_os_quota_get',          'builtin_role_admin','perm_os_quota_get'),
 ('rp_tadmin_os_quota_get',         'builtin_role_tenant_admin','perm_os_quota_get'),
 ('rp_tmember_os_quota_get',        'builtin_role_tenant_member','perm_os_quota_get'),

 ('rp_admin_os_credential_get',     'builtin_role_admin','perm_os_credential_get'),
 ('rp_tadmin_os_credential_get',    'builtin_role_tenant_admin','perm_os_credential_get'),
 ('rp_tmember_os_credential_get',   'builtin_role_tenant_member','perm_os_credential_get'),

 ('rp_admin_os_objects_list',       'builtin_role_admin','perm_os_objects_list'),
 ('rp_tadmin_os_objects_list',      'builtin_role_tenant_admin','perm_os_objects_list'),
 ('rp_tmember_os_objects_list',     'builtin_role_tenant_member','perm_os_objects_list'),

 ('rp_admin_os_objects_stat',       'builtin_role_admin','perm_os_objects_stat'),
 ('rp_tadmin_os_objects_stat',      'builtin_role_tenant_admin','perm_os_objects_stat'),
 ('rp_tmember_os_objects_stat',     'builtin_role_tenant_member','perm_os_objects_stat'),

 ('rp_admin_os_objects_download',   'builtin_role_admin','perm_os_objects_download'),
 ('rp_tadmin_os_objects_download',  'builtin_role_tenant_admin','perm_os_objects_download'),
 ('rp_tmember_os_objects_download', 'builtin_role_tenant_member','perm_os_objects_download'),

 ('rp_admin_os_bucket_create',      'builtin_role_admin','perm_os_bucket_create'),
 ('rp_admin_os_bucket_delete',      'builtin_role_admin','perm_os_bucket_delete'),
 ('rp_admin_os_quota_set',          'builtin_role_admin','perm_os_quota_set'),
 ('rp_admin_os_credential_issue',   'builtin_role_admin','perm_os_credential_issue'),
 ('rp_admin_os_credential_revoke',  'builtin_role_admin','perm_os_credential_revoke')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
