-- ============================================================================================
-- 应用网关端口池：端口分配表（2026-11-08）
-- ============================================================================================
-- 应用服务选「网关 HTTP / 网关 TCP」暴露时，平台从 Istio 组件配置的端口池里分配一个端口，
-- 写进本表；命名空间的 ListenerSet（platform-ingress）按本表的 ACTIVE 行重算。
-- 设计见 docs/superpowers/specs/2026-11-08-app-istio-exposure-design.md §5。
--
-- - (cluster_id, port) 唯一：一个端口同一时刻只属于一个服务；并发分配靠它兜底。
-- - 释放后行保留为 RELEASED（released_at 记时间）：冷却 10 分钟后才会被复用（复用 = 原地改回 ACTIVE）。
-- - 不加外键；不新增接口，所以没有权限行。
--
-- 幂等：CREATE TABLE IF NOT EXISTS。
-- ⚠️ 本仓没有 Flyway，手工重放；先重放本文件再起新构建（新 mapper 会读这张表）。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

CREATE TABLE IF NOT EXISTS platform_ingress_port (
  id            bigint       NOT NULL AUTO_INCREMENT,
  cluster_id    varchar(64)  NOT NULL,
  port          int          NOT NULL,
  namespace     varchar(63)  NOT NULL,
  app_id        varchar(64)  NOT NULL,
  service_key   varchar(64)  NOT NULL,
  protocol      varchar(8)   NOT NULL COMMENT 'HTTP / TCP（listener 协议）',
  status        varchar(16)  NOT NULL COMMENT 'ACTIVE / RELEASED',
  released_at   datetime     NULL COMMENT '释放时间；冷却期从这里算',
  created_at    datetime     NOT NULL,
  updated_at    datetime     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ingress_port (cluster_id, port),
  KEY idx_ingress_port_app (app_id, service_key),
  KEY idx_ingress_port_ns (cluster_id, namespace)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用网关端口池分配';
