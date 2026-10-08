-- ============================================================================================
-- StorageClass 收归平台管理员（修跨租户可读 / 无维度可收窄的资源）
-- ============================================================================================
-- 背景：/resource/storageclasses/** 原本给的是 tenant:storageclass:{list,get,yaml}，且绑在
-- builtin_role_tenant_admin / tenant_member 上（V2026_09_29_1 按 tenant:% 前缀批量绑定）。
-- 但 StorageClass 是<b>集群级对象、没有命名空间维度</b>：不像 PV 能按 spec.claimRef.namespace
-- 归属到租户，StorageClass 的 name/provisioner/parameters 是整集群共享的，服务层无从过滤。
-- 结论：该资源只能整体收归平台管理员（V2026_10_07 批次的安全修复）。
--
-- ⚠️ 提权陷阱（本文件的存在理由）：这三行的 <b>id 不变、只改 code</b>，而
--    platform_role_permission 是按 permission_id 关联、code 从被关联行读出。因此若只跑 UPDATE，
--    原本按 tenant:storageclass:* 建立的关联会"原地继承"新 code = 给所有租户角色凭空授予
--    platform:cluster:manage（= 平台管理员全权限）。必须先删关联。
--
-- 关联按 permission_id 全删（不按 role_id 白名单）：自定义角色若曾勾选 tenant:storageclass:*，
-- 同样必须一并清掉，否则一样是提权。admin 的可达性不受影响——它另有 perm_cluster_manage 等行
-- 授予同一 code（V2026_09_24_2「admin → 全部 platform:% 权限」）。
--
-- 前端后果（已知并接受）：租户侧失去 storageClassName 下拉候选（components/workload/PvcTemplateEditor.vue、
-- views/resource/PvcView.vue），退化为手填（两处均已 filterable，前者另有 allow-create）。若日后要恢复候选值，
-- 正确做法是新增窄投影只读端点（只返回 name/provisioner/reclaimPolicy/allowVolumeExpansion，
-- 不含 parameters —— 部分 provisioner 的 parameters 含凭据），而不是恢复整表可读。
--
-- 幂等：UPDATE 按 id、DELETE 按 permission_id，重复执行结果一致。
-- ============================================================================================

UPDATE platform_permission
SET code = 'platform:cluster:manage',
    description = CONCAT(description, '（平台管理员专属，V2026_10_07_3 收窄）')
WHERE id IN ('perm_res_sc_list', 'perm_res_sc_get', 'perm_res_sc_yaml')
  AND code LIKE 'tenant:storageclass:%';

DELETE rp FROM platform_role_permission rp
WHERE rp.permission_id IN ('perm_res_sc_list', 'perm_res_sc_get', 'perm_res_sc_yaml');
