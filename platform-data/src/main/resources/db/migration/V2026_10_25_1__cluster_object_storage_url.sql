-- ============================================================================================
-- 集群级「对象存储地址」（2026-10-25）
-- ============================================================================================
-- 背景：对象存储（SeaweedFS）是**每集群一套**的组件，而平台访问它的地址取决于两件事：
--   1) 哪个集群；2) 平台从哪一侧访问（平台在集群内 → 集群内 DNS；在集群外 → 节点暴露地址）。
-- 以前这个地址挂在组件安装表单 / 平台全局配置上，导致「多处都能配、且都只对单集群有效」。
--
-- 现在：地址是**每集群的连接配置**（与 prometheus_url / grafana_url 同形），
--   空 = 按组件安装位置推导：http://seaweedfs-s3.platform-storage:8333（见 ComponentRecipeCatalog）。
--
-- ⚠️ 本仓没有 Flyway，手工重放；幂等（information_schema 条件式 ADD COLUMN，MySQL 8 不支持
--    `ADD COLUMN IF NOT EXISTS`，那是 MariaDB 语法）。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'k8s_cluster'
               AND COLUMN_NAME = 'object_storage_url');
SET @ddl := IF(@col = 0,
  'ALTER TABLE k8s_cluster ADD COLUMN object_storage_url varchar(255) NULL COMMENT ''平台访问该集群对象存储(S3)的地址；空 = 按组件安装位置推导集群内地址'' ',
  'SET @cluster_os_url_skip = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

-- 值由运维在「集群管理 → 编辑」里填（不放任何环境相关的默认值到迁移里）：
--   平台在集群外 / 业务集群 → 填暴露后的地址，例如 http://192.168.31.80:38333；
--   平台在集群内且够得着 ClusterIP → 留空，按 recipe 推导 http://seaweedfs-s3.platform-storage:8333。
