-- 允许审计存放完整 S3 桶名（最多 63 字符）、分隔符与对象键（最多 1024 字节）。
ALTER TABLE `platform_audit_log`
    MODIFY COLUMN `target_name` VARCHAR(1088) DEFAULT NULL COMMENT '目标对象名或 S3 桶名/对象键';
