-- 资源域细粒度权限点 seed（V2026_09_29_1）：/resource/** 整体豁免 → 85 端点行 + 内置角色关联。
-- 幂等：显式 id 命中 PRIMARY KEY + ODKU，同 V2026_09_27_1 范式。
-- ⚠️ 部署顺序：先重放本文件再起新构建——ExemptPaths 收窄后，无行的 /resource/** 端点会被启动交叉校验拒启（fail-closed）。
-- 颗粒度：动词级 code；workload pause→:update、workload/pod metrics×4→:get 折叠；pod logs 独立 :logs。
-- nodes 16 端点复用既有 platform:cluster:manage（不新增 code）；SM/PM relabel-labels/metric-names 与 /resource/context 保持豁免（不在本文件）。

-- ===== workloads（11）=====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_res_wl_list','API','/resource/workloads/list','POST','tenant:workload:list','列出工作负载'),
 ('perm_res_wl_get','API','/resource/workloads/{name}','GET','tenant:workload:get','查看工作负载详情'),
 ('perm_res_wl_yaml','API','/resource/workloads/{name}/yaml','GET','tenant:workload:yaml','查看工作负载 YAML'),
 ('perm_res_wl_create','API','/resource/workloads','POST','tenant:workload:create','创建工作负载'),
 ('perm_res_wl_update','API','/resource/workloads/{name}','PUT','tenant:workload:update','更新工作负载'),
 ('perm_res_wl_pause','API','/resource/workloads/{name}/pause','POST','tenant:workload:update','暂停/恢复工作负载（共享 update 动词）'),
 ('perm_res_wl_delete','API','/resource/workloads/{name}','DELETE','tenant:workload:delete','删除工作负载'),
 ('perm_res_wl_metrics_cpu','API','/resource/workloads/{name}/metrics/cpu','POST','tenant:workload:get','工作负载 CPU 指标（共享 get 动词）'),
 ('perm_res_wl_metrics_memory','API','/resource/workloads/{name}/metrics/memory','POST','tenant:workload:get','工作负载内存指标（共享 get 动词）'),
 ('perm_res_wl_metrics_disk','API','/resource/workloads/{name}/metrics/disk','POST','tenant:workload:get','工作负载磁盘指标（共享 get 动词）'),
 ('perm_res_wl_metrics_network','API','/resource/workloads/{name}/metrics/network','POST','tenant:workload:get','工作负载网络指标（共享 get 动词）')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== pods（9；无 create/update）=====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_res_pod_list','API','/resource/pods/list','POST','tenant:pod:list','列出 Pod'),
 ('perm_res_pod_get','API','/resource/pods/{name}','GET','tenant:pod:get','查看 Pod 详情'),
 ('perm_res_pod_yaml','API','/resource/pods/{name}/yaml','GET','tenant:pod:yaml','查看 Pod YAML'),
 ('perm_res_pod_delete','API','/resource/pods/{name}','DELETE','tenant:pod:delete','删除 Pod'),
 ('perm_res_pod_logs','API','/resource/pods/{name}/logs','GET','tenant:pod:logs','查看 Pod 日志'),
 ('perm_res_pod_metrics_cpu','API','/resource/pods/{name}/metrics/cpu','POST','tenant:pod:get','Pod CPU 指标（共享 get 动词）'),
 ('perm_res_pod_metrics_memory','API','/resource/pods/{name}/metrics/memory','POST','tenant:pod:get','Pod 内存指标（共享 get 动词）'),
 ('perm_res_pod_metrics_disk','API','/resource/pods/{name}/metrics/disk','POST','tenant:pod:get','Pod 磁盘指标（共享 get 动词）'),
 ('perm_res_pod_metrics_network','API','/resource/pods/{name}/metrics/network','POST','tenant:pod:get','Pod 网络指标（共享 get 动词）')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== configmaps（6）=====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_res_cm_list','API','/resource/configmaps/list','POST','tenant:configmap:list','列出 ConfigMap'),
 ('perm_res_cm_get','API','/resource/configmaps/{name}','GET','tenant:configmap:get','查看 ConfigMap 详情'),
 ('perm_res_cm_yaml','API','/resource/configmaps/{name}/yaml','GET','tenant:configmap:yaml','查看 ConfigMap YAML'),
 ('perm_res_cm_create','API','/resource/configmaps','POST','tenant:configmap:create','创建 ConfigMap'),
 ('perm_res_cm_update','API','/resource/configmaps/{name}','PUT','tenant:configmap:update','更新 ConfigMap'),
 ('perm_res_cm_delete','API','/resource/configmaps/{name}','DELETE','tenant:configmap:delete','删除 ConfigMap')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== secrets（6）=====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_res_sec_list','API','/resource/secrets/list','POST','tenant:secret:list','列出 Secret'),
 ('perm_res_sec_get','API','/resource/secrets/{name}','GET','tenant:secret:get','查看 Secret 详情'),
 ('perm_res_sec_yaml','API','/resource/secrets/{name}/yaml','GET','tenant:secret:yaml','查看 Secret YAML'),
 ('perm_res_sec_create','API','/resource/secrets','POST','tenant:secret:create','创建 Secret'),
 ('perm_res_sec_update','API','/resource/secrets/{name}','PUT','tenant:secret:update','更新 Secret'),
 ('perm_res_sec_delete','API','/resource/secrets/{name}','DELETE','tenant:secret:delete','删除 Secret')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== services（6）=====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_res_svc_list','API','/resource/services/list','POST','tenant:service:list','列出 Service'),
 ('perm_res_svc_get','API','/resource/services/{name}','GET','tenant:service:get','查看 Service 详情'),
 ('perm_res_svc_yaml','API','/resource/services/{name}/yaml','GET','tenant:service:yaml','查看 Service YAML'),
 ('perm_res_svc_create','API','/resource/services','POST','tenant:service:create','创建 Service'),
 ('perm_res_svc_update','API','/resource/services/{name}','PUT','tenant:service:update','更新 Service'),
 ('perm_res_svc_delete','API','/resource/services/{name}','DELETE','tenant:service:delete','删除 Service')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== pvcs（6）=====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_res_pvc_list','API','/resource/pvcs/list','POST','tenant:pvc:list','列出 PVC'),
 ('perm_res_pvc_get','API','/resource/pvcs/{name}','GET','tenant:pvc:get','查看 PVC 详情'),
 ('perm_res_pvc_yaml','API','/resource/pvcs/{name}/yaml','GET','tenant:pvc:yaml','查看 PVC YAML'),
 ('perm_res_pvc_create','API','/resource/pvcs','POST','tenant:pvc:create','创建 PVC'),
 ('perm_res_pvc_update','API','/resource/pvcs/{name}','PUT','tenant:pvc:update','更新 PVC'),
 ('perm_res_pvc_delete','API','/resource/pvcs/{name}','DELETE','tenant:pvc:delete','删除 PVC')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== servicemonitors（6；relabel-labels/metric-names 豁免不建行）=====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_res_sm_list','API','/resource/servicemonitors/list','POST','tenant:servicemonitor:list','列出 ServiceMonitor'),
 ('perm_res_sm_get','API','/resource/servicemonitors/{name}','GET','tenant:servicemonitor:get','查看 ServiceMonitor 详情'),
 ('perm_res_sm_yaml','API','/resource/servicemonitors/{name}/yaml','GET','tenant:servicemonitor:yaml','查看 ServiceMonitor YAML'),
 ('perm_res_sm_create','API','/resource/servicemonitors','POST','tenant:servicemonitor:create','创建 ServiceMonitor'),
 ('perm_res_sm_update','API','/resource/servicemonitors/{name}','PUT','tenant:servicemonitor:update','更新 ServiceMonitor'),
 ('perm_res_sm_delete','API','/resource/servicemonitors/{name}','DELETE','tenant:servicemonitor:delete','删除 ServiceMonitor')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== podmonitors（6；relabel-labels/metric-names 豁免不建行）=====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_res_pm_list','API','/resource/podmonitors/list','POST','tenant:podmonitor:list','列出 PodMonitor'),
 ('perm_res_pm_get','API','/resource/podmonitors/{name}','GET','tenant:podmonitor:get','查看 PodMonitor 详情'),
 ('perm_res_pm_yaml','API','/resource/podmonitors/{name}/yaml','GET','tenant:podmonitor:yaml','查看 PodMonitor YAML'),
 ('perm_res_pm_create','API','/resource/podmonitors','POST','tenant:podmonitor:create','创建 PodMonitor'),
 ('perm_res_pm_update','API','/resource/podmonitors/{name}','PUT','tenant:podmonitor:update','更新 PodMonitor'),
 ('perm_res_pm_delete','API','/resource/podmonitors/{name}','DELETE','tenant:podmonitor:delete','删除 PodMonitor')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== hpas（6）=====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_res_hpa_list','API','/resource/hpas/list','POST','tenant:hpa:list','列出 HPA'),
 ('perm_res_hpa_get','API','/resource/hpas/{name}','GET','tenant:hpa:get','查看 HPA 详情'),
 ('perm_res_hpa_yaml','API','/resource/hpas/{name}/yaml','GET','tenant:hpa:yaml','查看 HPA YAML'),
 ('perm_res_hpa_create','API','/resource/hpas','POST','tenant:hpa:create','创建 HPA'),
 ('perm_res_hpa_update','API','/resource/hpas/{name}','PUT','tenant:hpa:update','更新 HPA'),
 ('perm_res_hpa_delete','API','/resource/hpas/{name}','DELETE','tenant:hpa:delete','删除 HPA')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== persistentvolumes（3，只读）=====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_res_pv_list','API','/resource/persistentvolumes/list','POST','tenant:persistentvolume:list','列出 PV'),
 ('perm_res_pv_get','API','/resource/persistentvolumes/{name}','GET','tenant:persistentvolume:get','查看 PV 详情'),
 ('perm_res_pv_yaml','API','/resource/persistentvolumes/{name}/yaml','GET','tenant:persistentvolume:yaml','查看 PV YAML')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== storageclasses（3，只读）=====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_res_sc_list','API','/resource/storageclasses/list','POST','tenant:storageclass:list','列出 StorageClass'),
 ('perm_res_sc_get','API','/resource/storageclasses/{name}','GET','tenant:storageclass:get','查看 StorageClass 详情'),
 ('perm_res_sc_yaml','API','/resource/storageclasses/{name}/yaml','GET','tenant:storageclass:yaml','查看 StorageClass YAML')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== nodes（16；集群级操作，复用既有 platform:cluster:manage，不新增 code）=====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_res_node_list','API','/resource/nodes/list','POST','platform:cluster:manage','列出节点'),
 ('perm_res_node_get','API','/resource/nodes/{name}','GET','platform:cluster:manage','查看节点详情'),
 ('perm_res_node_yaml','API','/resource/nodes/{name}/yaml','GET','platform:cluster:manage','查看节点 YAML'),
 ('perm_res_node_cordon','API','/resource/nodes/cordon','POST','platform:cluster:manage','封锁节点'),
 ('perm_res_node_uncordon','API','/resource/nodes/uncordon','POST','platform:cluster:manage','解除封锁节点'),
 ('perm_res_node_update','API','/resource/nodes/{name}','PUT','platform:cluster:manage','更新节点（标签/污点）'),
 ('perm_res_node_drain','API','/resource/nodes/drain','POST','platform:cluster:manage','排空节点'),
 ('perm_res_node_podstats','API','/resource/nodes/podstats','GET','platform:cluster:manage','节点 Pod 统计'),
 ('perm_res_node_pods','API','/resource/nodes/{name}/pods','GET','platform:cluster:manage','列出节点上的 Pod'),
 ('perm_res_node_pod_yaml','API','/resource/nodes/{name}/pods/{namespace}/{podName}/yaml','GET','platform:cluster:manage','查看节点上 Pod YAML'),
 ('perm_res_node_pod_logs','API','/resource/nodes/{name}/pods/{namespace}/{podName}/logs','GET','platform:cluster:manage','查看节点上 Pod 日志'),
 ('perm_res_node_events','API','/resource/nodes/{name}/events','GET','platform:cluster:manage','节点事件'),
 ('perm_res_node_metrics_current','API','/resource/nodes/metrics/current','POST','platform:cluster:manage','节点当前指标'),
 ('perm_res_node_metrics_cpu','API','/resource/nodes/{name}/metrics/cpu','POST','platform:cluster:manage','节点 CPU 指标'),
 ('perm_res_node_metrics_memory','API','/resource/nodes/{name}/metrics/memory','POST','platform:cluster:manage','节点内存指标'),
 ('perm_res_node_metrics_disk','API','/resource/nodes/{name}/metrics/disk','POST','platform:cluster:manage','节点磁盘指标'),
 ('perm_res_node_metrics_network','API','/resource/nodes/{name}/metrics/network','POST','platform:cluster:manage','节点网络指标')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== 内置角色关联（一次性，同 V2026_09_24_2 范式；新 code 不会自动挂角色，漏挂 = 租户族部署后全 403）=====
-- tenant-admin → 全部资源域 tenant:* 行（59 code / 68 行）
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_tadmin_res_', p.id), 'builtin_role_tenant_admin', p.id
FROM platform_permission p
WHERE p.id LIKE 'perm_res_%' AND p.code LIKE 'tenant:%' AND p.deleted_at IS NULL
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- tenant-member → 仅读动词（list/get/yaml/logs），排除 secrets——能否读 secret 由角色关联控制（admin 可在 RoleView 授予）
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_tmember_res_', p.id), 'builtin_role_tenant_member', p.id
FROM platform_permission p
WHERE p.id LIKE 'perm_res_%' AND p.code LIKE 'tenant:%' AND p.code NOT LIKE 'tenant:secret:%'
  AND (p.code LIKE '%:list' OR p.code LIKE '%:get' OR p.code LIKE '%:yaml' OR p.code LIKE '%:logs')
  AND p.deleted_at IS NULL
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
