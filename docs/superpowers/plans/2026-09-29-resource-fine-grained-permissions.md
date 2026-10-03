# 资源域细粒度权限点（表驱动）实施计划

For agentic workers: 按 Task 顺序执行，TDD（先红后绿），每个 Task 完成后跑对应验证命令再进下一个。代码均给出成品文本，勿改写语义；完成后汇报测试输出。

**Goal**: `/resource/**` 从整体豁免改为 API 侧表驱动细粒度权限点（动词级 code），prom discovery 端点保持豁免；内置角色在同一迁移里 seed 关联，避免部署后租户族全员 403。

## 背景与现状

- `ExemptPaths.PREFIXES` 含 `/resource/**` → 90 个资源端点当前全部「authenticated 即过」，无 code 区分；
- `PermissionAuthorizationManager.authorize()` 实际先查豁免、后查权限行（L42-48），与 spec §5.1「权限行命中 > 豁免 > 默认拒绝」及其自身 javadoc 不一致 —— 本计划 Task 1 顺手重排，使权限行可以收紧豁免路径；
- 内置角色关联是 seed 时一次性 `INSERT...SELECT`（V2026_09_24_2）：新 code **不会**自动挂到内置角色 → 必须随本迁移补关联，否则 tenant-admin/member 部署后资源页全 403；
- 现有 JWT 的 permissions claim 是签发时的闭包快照 → 部署后旧 token 缺新 code，靠 session-renewal 续期/重登自愈（写入 runbook §3）。

## 颗粒度裁定（用户已确认）

- **动词级**：list/get/yaml/create/update/delete 独立成 code；workload `pause`→`:update`、workload/pod `metrics×4`→`:get` 折叠；pod `logs` 独立 `:logs`；
- **nodes** 16 端点复用既有 `platform:cluster:manage`（不新增平台 code，admin 经 code 字符串闭包天然覆盖）；
- **prom discovery**（SM/PM 的 relabel-labels + metric-names，4 端点）保持豁免，后续需要再加；
- **secrets**：读动词存在为 code，但默认**不**关联 tenant-member——能否读由角色关联控制（admin 可在 RoleView 授予）。

## Code 总表（59 个新 tenant code / 85 行）

| 资源 | codes | 端点数 |
|---|---|---|
| workload | list/get/yaml/create/update/delete（pause→update，metrics×4→get） | 11 |
| pod | list/get/yaml/logs/delete（metrics×4→get；无 create/update） | 9 |
| configmap / secret / service / pvc / servicemonitor / podmonitor / hpa | 各 list/get/yaml/create/update/delete | 6×7=42 |
| persistentvolume / storageclass | list/get/yaml | 3+3 |
| nodes | 全部 → `platform:cluster:manage`（既有 code） | 17 |

豁免保留 5 条：`/resource/context` + SM/PM 各 2 个 discovery 端点。

## 关键机制（实施依据，勿改动语义）

- `PermissionRegistry.requiredCodes` 用 AntPathMatcher：DB 行 resource 列存**字面 pattern**（如 `/resource/workloads/{name}`），运行时实际路径 ant-match；
- 启动交叉校验（`PermissionCrossCheck.uncoveredEndpoints`）拿 live endpoint 的 pattern 与规则比对，两侧都含字面 `{name}` → 精确对齐，85 行 ↔ 85 端点一一对应；漏一行 = fail-closed 拒启；
- Manager 重排后：命中权限行 → ANY-of（无 code = deny，**不回落豁免**）；未命中行 → 豁免路径 authenticated 过；否则默认拒绝。

## Global Constraints

- **不 commit**（用户自行提交）；勿动仓库里其他未提交文件（尤其与本计划无关的 modified 文件）；
- 迁移命名 `V2026_09_29_1__resource_fine_grained.sql`，幂等范式同 V2026_09_27_1：显式主键 id + `ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action)`；
- 部署顺序 = 安全线：**先重放迁移再起新构建**（ExemptPaths 收窄后，无行的 /resource/** 端点会被启动交叉校验拒启）；
- 前端门控只是体验层，权威在后端表驱动授权；code 拼写经 `permCodes.ts` 常量收口防手误；
- 汇报/注释用中文。

## Task 1 — Manager 重排：权限行命中优先于豁免（TDD）

**Files**:
- Modify: `platform-api/src/test/java/com/coding/platformapi/security/PermissionAuthorizationManagerTest.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/security/PermissionAuthorizationManager.java`

- [x] **Step 1.1** 在 `PermissionAuthorizationManagerTest` 类尾（`denied_when_no_rule_and_not_exempt` 之后）追加两个测试（所需 import 均已存在：mock/when/Optional/Set）：

```java
    @Test
    void row_on_exempt_path_denied_when_code_not_held() {
        // spec §5.1：权限行命中 > 豁免 —— 行可收紧豁免路径；命中行但无 code → 拒，不回落豁免
        PermissionRegistry reg = mock(PermissionRegistry.class);
        when(reg.requiredCodes("GET", "/resource/context"))
                .thenReturn(Optional.of(Set.of("tenant:workload:list")));
        var mgr = new PermissionAuthorizationManager(reg);
        AuthorizationResult d = mgr.authorize(() -> auth("PERM:platform:cluster:manage"),
                ctx("GET", "/resource/context"));
        assertThat(d.isGranted()).isFalse();
    }

    @Test
    void row_on_exempt_path_granted_when_code_held() {
        PermissionRegistry reg = mock(PermissionRegistry.class);
        when(reg.requiredCodes("GET", "/resource/context"))
                .thenReturn(Optional.of(Set.of("tenant:workload:list")));
        var mgr = new PermissionAuthorizationManager(reg);
        AuthorizationResult d = mgr.authorize(() -> auth("PERM:tenant:workload:list"),
                ctx("GET", "/resource/context"));
        assertThat(d.isGranted()).isTrue();
    }
```

- [x] **Step 1.2** 跑红：`mvn test -pl platform-api -Dtest=PermissionAuthorizationManagerTest` —— `row_on_exempt_path_denied_when_code_not_held` 必失败（旧代码先查豁免直接放行），其余通过。
- [x] **Step 1.3** 用下面整段替换 `authorize()` 方法体（签名与 javadoc 不动）：

```java
    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authSupplier,
                                         RequestAuthorizationContext ctx) {
        Authentication auth = authSupplier.get();
        // AnonymousAuthenticationToken.isAuthenticated() 恒为 true（AnonymousAuthenticationFilter 默认开启），
        // 故须显式排除匿名，否则未登录可命中豁免路径（spec §5.1 豁免=仅 authenticated）。
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return new AuthorizationDecision(false);
        }
        String method = ctx.getRequest().getMethod();
        String path = pathOf(ctx);
        // 权限行命中优先于豁免（spec §5.1）：行可收紧豁免路径；命中行但无 code → 直接拒，不回落豁免
        Set<String> required = registry.requiredCodes(method, path).orElse(null);
        if (required != null) {
            for (String code : required) {
                String authority = PermissionAuthorityNames.perm(code);
                boolean ok = auth.getAuthorities().stream()
                        .anyMatch(g -> g.getAuthority().equals(authority));
                if (ok) {
                    return new AuthorizationDecision(true);
                }
            }
            return new AuthorizationDecision(false);
        }
        if (ExemptPaths.isExempt(path)) {
            return new AuthorizationDecision(true);
        }
        return new AuthorizationDecision(false); // 默认拒绝（§5.3 交叉校验本不应让这发生）
    }
```

- [x] **Step 1.4** 跑绿：同 Step 1.2 命令，9 个测试全过。

## Task 2 — ExemptPaths 收窄（TDD）

**Files**:
- Modify: `platform-api/src/main/java/com/coding/platformapi/security/ExemptPaths.java`
- Modify: `platform-api/src/test/java/com/coding/platformapi/security/ExemptPathsTest.java`
- Modify: `platform-api/src/test/java/com/coding/platformapi/security/PermissionAuthorizationManagerTest.java`（一行）

- [x] **Step 2.1** `ExemptPathsTest.exempt_paths_match`：删除首行
  `assertThat(ExemptPaths.isExempt("/resource/deployment/list")).isTrue();`
  并在类尾追加新测试方法：

```java
    @Test
    void resource_api_paths_no_longer_exempt() {
        // /resource/** 整体豁免已移除（V2026_09_29_1）：仅 context + prom discovery 四端点保留豁免
        assertThat(ExemptPaths.isExempt("/resource/context")).isTrue();
        assertThat(ExemptPaths.isExempt("/resource/servicemonitors/relabel-labels")).isTrue();
        assertThat(ExemptPaths.isExempt("/resource/servicemonitors/metric-names")).isTrue();
        assertThat(ExemptPaths.isExempt("/resource/podmonitors/relabel-labels")).isTrue();
        assertThat(ExemptPaths.isExempt("/resource/podmonitors/metric-names")).isTrue();
        assertThat(ExemptPaths.isExempt("/resource/workloads/list")).isFalse();
        assertThat(ExemptPaths.isExempt("/resource/pods/nginx-1/logs")).isFalse();
        assertThat(ExemptPaths.isExempt("/resource/nodes/cordon")).isFalse();
        assertThat(ExemptPaths.isExempt("/resource/secrets/{name}")).isFalse();
    }
```

- [x] **Step 2.2** 跑红：`mvn test -pl platform-api -Dtest=ExemptPathsTest` —— `resource_api_paths_no_longer_exempt` 失败。
- [x] **Step 2.3** `ExemptPaths.PREFIXES` 整段替换为（类 javadoc 追加一句「/resource/** 整体豁免已于 2026-09-29 移除，仅保留 context 与 prom discovery 四端点」）：

```java
    private static final String[] PREFIXES = {
            "/resource/context",
            "/resource/servicemonitors/relabel-labels",
            "/resource/servicemonitors/metric-names",
            "/resource/podmonitors/relabel-labels",
            "/resource/podmonitors/metric-names",
            "/user/me", "/user/my-tenants", "/callback", "/error",
            "/actuator/**", "/doc.html", "/swagger-ui/**", "/swagger-ui.html",
            "/v3/api-docs/**", "/v3/api-docs.yaml", "/v3/api-docs.yaml/**",
            "/favicon.ico",
            "/ws/**"
    };
```

- [x] **Step 2.4** 修既有 manager 测试 `exempt_path_granted_for_authenticated_without_any_rule`：路径 `/resource/pods` → `/resource/context`（收窄后 /resource/pods 是规则目标，mock registry 无行 → 默认拒绝，原断言会破）。改动一行：
  `ctx("GET", "/resource/pods")` → `ctx("GET", "/resource/context")`
- [x] **Step 2.5** 跑绿：`mvn test -pl platform-api -Dtest='ExemptPathsTest,PermissionAuthorizationManagerTest'` 全过。

## Task 3 — 迁移 V2026_09_29_1（85 行 + 内置角色关联）+ runbook

**Files**:
- Create: `platform-data/src/main/resources/db/migration/V2026_09_29_1__resource_fine_grained.sql`
- Modify: `docs/superpowers/plans/2026-09-24-rbac-management-runbook.md`

本 Task 无 Java 变更，验证靠「行数清点 + Step 3.5」（SQL 不随 mvn 执行）。文件内容按下面四段依次拼接（Step 3.1 – 3.4），全部行必须一字不差落齐——漏一行 = 部署时 fail-closed 拒启。

- [x] **Step 3.1** 文件头 + workloads(11) + pods(9)：

```sql
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
```

- [x] **Step 3.2** 追加 configmaps(6) + secrets(6) + services(6) + pvcs(6)（四段结构相同，仅资源名/code/描述不同）：

```sql
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
```

- [x] **Step 3.3** 追加 servicemonitors(6) + podmonitors(6) + hpas(6) + persistentvolumes(3) + storageclasses(3)。SM/PM 的 relabel-labels/metric-names **不建行**（豁免）：

```sql
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
```

- [x] **Step 3.4** 追加 nodes(17，全部复用 `platform:cluster:manage`) + 内置角色关联（两段）：

```sql
-- ===== nodes（17；集群级操作，复用既有 platform:cluster:manage，不新增 code）=====
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
```

- [x] **Step 3.5** 行数清点：文件内 `INSERT INTO platform_permission` 共 12 段、数据行合计 **85**（11+9+6×7+3+3+17）；`INSERT INTO platform_role_permission` 共 2 段。用 grep 数一遍再进 Task 4。

- [x] **Step 3.6** 改 runbook `docs/superpowers/plans/2026-09-24-rbac-management-runbook.md`，四处：

1. Step 2 source 序列，在 `source V2026_09_27_1__permission_manage.sql;` 行后追加一行：
```sql
source V2026_09_29_1__resource_fine_grained.sql;          -- 资源域细粒度权限：85 个 /resource/** 端点行 + 内置角色关联（先于新构建，漏跑则交叉校验拒启动）
```
2. 「全新环境」句：`照跑 V1 + V2 + V2026_09_26_1 + V2026_09_27_1` → `照跑 V1 + V2 + V2026_09_26_1 + V2026_09_27_1 + V2026_09_29_1`
3. 同段「不可省」句：`V2（及 B3 的 V2026_09_26_1、权限点管理的 V2026_09_27_1）不可省` → `V2（及 B3 的 V2026_09_26_1、权限点管理的 V2026_09_27_1、资源域细粒度的 V2026_09_29_1）不可省`
4. §3 已知缺口表追加一行：

```markdown
| 存量 token 缺新 code | 资源域 59 个新 code 只在签发时进 permissions 闭包；部署后旧 JWT 无新 code → 资源页暂时 403/菜单隐藏，session-renewal 续期或重登后自愈（无需人工干预） |
```

## Task 4 — 前端 permCodes.ts + 路由门控

**Files**:
- Create: `platform-web/src/permCodes.ts`
- Modify: `platform-web/src/router/index.ts`

- [x] **Step 4.1** 新建 `platform-web/src/permCodes.ts`（只收口本计划引用的 code，避免死代码）：

```ts
/**
 * 资源域权限点 code 常量（与 seed V2026_09_29_1__resource_fine_grained.sql 对齐）。
 * 权威门控在后端表驱动授权；此文件只保证路由 meta.requiresPerm 与菜单 v-if 拼写一致。
 */
export const resCodes = {
  workload: { list: 'tenant:workload:list', get: 'tenant:workload:get', create: 'tenant:workload:create', update: 'tenant:workload:update' },
  pod: { list: 'tenant:pod:list', get: 'tenant:pod:get', logs: 'tenant:pod:logs' },
  configmap: { list: 'tenant:configmap:list', create: 'tenant:configmap:create', update: 'tenant:configmap:update' },
  secret: { list: 'tenant:secret:list', create: 'tenant:secret:create', update: 'tenant:secret:update' },
  service: { list: 'tenant:service:list', create: 'tenant:service:create', update: 'tenant:service:update' },
  pvc: { list: 'tenant:pvc:list' },
  servicemonitor: { list: 'tenant:servicemonitor:list', create: 'tenant:servicemonitor:create', update: 'tenant:servicemonitor:update' },
  podmonitor: { list: 'tenant:podmonitor:list', create: 'tenant:podmonitor:create', update: 'tenant:podmonitor:update' },
  hpa: { list: 'tenant:hpa:list', create: 'tenant:hpa:create', update: 'tenant:hpa:update' },
} as const
```

- [x] **Step 4.2** `router/index.ts`：顶部 import 区加 `import { resCodes } from '@/permCodes'`；给 19 条 resources/* 路由的 meta 追加 `requiresPerm`（其余 title/group/context 原样保留）。示例（workloads 列表行改后）：

```ts
{ path: 'resources/workloads', name: 'workloads', component: () => import('@/views/resource/WorkloadView.vue'), meta: { title: '工作负载', group: '资源管理', context: 'full', requiresPerm: [resCodes.workload.list] } },
```

其余 18 条按此表追加（ANY-of 语义，editor = create|update）：

| 路由 path | requiresPerm |
|---|---|
| `resources/workloads/detail` | `[resCodes.workload.get]` |
| `resources/workloads/editor` | `[resCodes.workload.create, resCodes.workload.update]` |
| `resources/pods` | `[resCodes.pod.list]` |
| `resources/pods/detail` | `[resCodes.pod.get]` |
| `resources/pods/container` | `[resCodes.pod.get, resCodes.pod.logs]` |
| `resources/configmaps` | `[resCodes.configmap.list]` |
| `resources/configmaps/editor` | `[resCodes.configmap.create, resCodes.configmap.update]` |
| `resources/secrets` | `[resCodes.secret.list]` |
| `resources/secrets/editor` | `[resCodes.secret.create, resCodes.secret.update]` |
| `resources/services` | `[resCodes.service.list]` |
| `resources/services/editor` | `[resCodes.service.create, resCodes.service.update]` |
| `resources/pvcs` | `[resCodes.pvc.list]` |
| `resources/servicemonitors` | `[resCodes.servicemonitor.list]` |
| `resources/servicemonitors/editor` | `[resCodes.servicemonitor.create, resCodes.servicemonitor.update]` |
| `resources/podmonitors` | `[resCodes.podmonitor.list]` |
| `resources/podmonitors/editor` | `[resCodes.podmonitor.create, resCodes.podmonitor.update]` |
| `resources/hpas` | `[resCodes.hpa.list]` |
| `resources/hpas/editor` | `[resCodes.hpa.create, resCodes.hpa.update]` |

PV/StorageClass 无路由（页面未交付），不处理。守卫逻辑（beforeEach hasAny）已存在，无需改动。

## Task 5 — MainLayout 菜单门控

**Files**: Modify: `platform-web/src/layouts/MainLayout.vue`

- [x] **Step 5.1** `<script setup>` import 区加 `import { resCodes } from '@/permCodes'`（`can(code)` 本地函数已存在，模板可直接用 `can(resCodes.x.list)`）。
- [x] **Step 5.2** 把模板中「工作负载」分组起至「监控告警」组末项的整段（当前 L181-L225：五个无门控 group + 九个无 v-if 的 menu-item）替换为下面整段——菜单项按各自 list code 门控，分组标题按成员 OR（与「平台管理」组 L142 同范式）：

```html
        <div v-if="can(resCodes.workload.list) || can(resCodes.pod.list) || can(resCodes.hpa.list)" class="menu-group">工作负载</div>
        <el-menu-item v-if="can(resCodes.workload.list)" index="/resources/workloads">
          <el-icon><Box /></el-icon>
          <template #title>工作负载</template>
        </el-menu-item>
        <el-menu-item v-if="can(resCodes.pod.list)" index="/resources/pods">
          <el-icon><Cpu /></el-icon>
          <template #title>Pod</template>
        </el-menu-item>
        <el-menu-item v-if="can(resCodes.hpa.list)" index="/resources/hpas">
          <el-icon><TrendCharts /></el-icon>
          <template #title>HPA</template>
        </el-menu-item>

        <div v-if="can(resCodes.service.list)" class="menu-group">服务发现</div>
        <el-menu-item v-if="can(resCodes.service.list)" index="/resources/services">
          <el-icon><Link /></el-icon>
          <template #title>Service</template>
        </el-menu-item>

        <div v-if="can(resCodes.configmap.list) || can(resCodes.secret.list)" class="menu-group">配置管理</div>
        <el-menu-item v-if="can(resCodes.configmap.list)" index="/resources/configmaps">
          <el-icon><Document /></el-icon>
          <template #title>ConfigMap</template>
        </el-menu-item>
        <el-menu-item v-if="can(resCodes.secret.list)" index="/resources/secrets">
          <el-icon><Key /></el-icon>
          <template #title>Secret</template>
        </el-menu-item>

        <div v-if="can(resCodes.pvc.list)" class="menu-group">存储</div>
        <el-menu-item v-if="can(resCodes.pvc.list)" index="/resources/pvcs">
          <el-icon><Coin /></el-icon>
          <template #title>PVC</template>
        </el-menu-item>

        <div v-if="can(resCodes.servicemonitor.list) || can(resCodes.podmonitor.list)" class="menu-group">监控告警</div>
        <el-menu-item v-if="can(resCodes.servicemonitor.list)" index="/resources/servicemonitors">
          <el-icon><DataLine /></el-icon>
          <template #title>ServiceMonitor</template>
        </el-menu-item>
        <el-menu-item v-if="can(resCodes.podmonitor.list)" index="/resources/podmonitors">
          <el-icon><DataLine /></el-icon>
          <template #title>PodMonitor</template>
        </el-menu-item>
```

「平台管理」「集群运维」两组不动。页内按钮（创建/编辑/删除）不加前端门控——与现有页面一致，由后端 403 + http 拦截器 toast 兜底。

## Task 6 — 验证与部署

- [x] **Step 6.1** 后端全量测试（本计划未改 platform-common/platform-data 的 Java，无需重装 .m2）：
  `mvn test -pl platform-api` —— 预期全绿（原 113 + 新增 3 = 116）。
- [x] **Step 6.2** 前端构建（含 vue-tsc 类型检查）：
  `cd platform-web && npm run build` —— 预期通过。
- [x] **Step 6.3** 汇报：测试计数、build 结果、改动文件清单。**不 commit。**
- [ ] **Step 6.4**（用户侧，agent 不执行）部署按 runbook 顺序：停 api+auth → 重放 `V2026_09_29_1` → 起 auth 再起 api → 日志确认 `[RBAC] 交叉校验通过` → 冒烟：
  - admin（续期后 token）：资源页全功能正常；
  - tenant-member：列表/详情/YAML/日志可见，Secret 菜单与页面不可见（未关联），创建/编辑入口路由守卫回落总览；
  - tenant-admin：含 Secret 读、全部写动词可用；
  - 旧 token 未续期前资源页 403/菜单隐藏属预期（runbook §3 新增行）。

  注：启动自检日志的 endpoint 总数与部署前相同（90 个资源端点一直在枚举内，只是从「豁免」转为「有行」）；漏跑迁移时这里会直接拒启，那才是异常信号。

## Self-Review 清单（执行完对照）

- [x] 85 行 ↔ 13 个 controller 的 mapping 注解一一对应（grep `@*Mapping` 复核，含 pause/metrics/logs/podstats/events 等易漏项）；脚本核账：SQL 85 对全部命中，controller 侧差集恰为 5 个豁免端点；
- [ ] ExemptPaths 收窄后无任何 /resource/** 端点既无行又非豁免（静态核账已过；最终证明 = Step 6.4 启动自检通过）;
- [x] 两个内置角色关联的 WHERE 条件不会误纳 nodes 行（`p.code LIKE 'tenant:%'` 已排除 `platform:cluster:manage`）；
- [x] 前端 19 条路由 + 9 个菜单项 + 5 个分组标题全部走 `resCodes` 常量，无裸字符串新引入；
- [x] 未触碰本计划外的未提交文件。

## 执行偏差记录（2026-09-29）

1. nodes 实为 **17** 个端点（计划初稿误计 16）→ 总行数 **85**（非 84），SQL 内容本就按 controller 全量落齐，仅计数标签已修正；
2. `PermissionCrossCheckTest.exempt_endpoints_not_flagged_even_without_rule` 也用了 `/resource/**` 当豁免样例（计划未预见）→ 样例路径改 `/resource/context`，一行修复；
3. **admin 关联缺口**（部署后用户报告"平台管理员看不到 resource 资源"，重登后仍复现）：59 个新 code 全是 `tenant:*` 族，而内置关联里 admin 只有 `platform:%`（V2026_09_24_2），V2026_09_29_1 只补了 tenant-admin/tenant-member → admin 对 85 个资源行零关联。修复 = 新增 **V2026_09_29_2__admin_resource_linkage.sql**（admin → 全部 `perm_res_%`，显式 id `rp_admin_res_*` + ODKU 幂等），dev 库已执行验证（85 行，重跑 0 新增，admin tenant:* code = 59）；
4. **generator mapper insert 缺 id**（部署后用户报告"新增角色失败"）：`PlatformRoleMapper.insert` / `PlatformPermissionMapper.insert` 是 generator 样板——漏了 `id` 列却带 `useGeneratedKeys="true"`，而 platform_* 表全是 varchar(64) 手工 PK（无自增），service 里 `ULIDGenerator.generateULID()` 被静默丢弃 → MySQL 1048。两处 insert 补上 id 列并去掉 useGeneratedKeys（`insertSelective` 变体是死代码，未动）；
5. **admin 在角色管理页看不到资源权限 + 保存必错**（部署后用户报告"内置平台管理员的角色管理中看不到 resource 资源"）：RoleView 勾选树按角色 scope 过滤 code 族（PLATFORM 只见 `platform:`），且后端 `assertScopeMatches` 同规则——V2026_09_29_2 让 admin 持有 tenant:* 后，①admin 树里 59 个资源 code 被滤掉；②更隐蔽的是全量重存会回显并回传这些 code → `role/permission/save` 对 admin 必抛 ROLE_SCOPE_MISMATCH。修复 = scope 不变量**豁免内置超管 admin**：后端 `assertScopeMatches(PlatformRole, code)` 对 code=admin 放开 PLATFORM-only（TDD：拒用例改自定义平台角色 + 新增 admin 双族保存用例，117/117 绿）；前端 RoleView `grouped` 对 admin 不过滤 + 提示文案区分。spec §3.4 不变量 1 已同步加豁免条款（2026-09-30 修订）。

