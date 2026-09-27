# RBAC 管理面 — 部署 Runbook（2026-09-24-rbac-management）

适用分支：`worktree-rbac-management`（合并至 main 后同样适用）。
本 runbook 是**安全线**，不只是运维步骤——顺序错了会出安全事故或全员锁死。

## 0. 为什么部署顺序是安全线（必读）

- **auth-server 必须与 platform-api 同批重启**。旧构建的 auth-server 签发的 token：
  1. **无 `data.permissions` claim** → platform-api 新授权层 fail-closed，全员管理端点 403 锁死；
  2. **无 tenantInfo / 租户闭包** → 若只重启 platform-api 而 auth-server 仍旧构建，旧 token 语义与
     新 `TenantContextResolver` 双路径校验不匹配（代管/自管判定错乱）。
- **platform-api 启动前必须先执行 migration**。空权限表 → PermissionRegistry 0 规则 →
  交叉校验把所有管理端点判"裸奔" → **拒绝启动**（这是 fail-closed 设计意图，不是故障）。

## 1. 部署步骤（严格按序）

### Step 1 — 停服
停 `platform-api` **和** `platform-auth`（auth-server）。两者都停，避免新旧构建混跑签发。

### Step 2 — 数据库 migration（手工执行，无 flyway）
按序执行：

```sql
source V2026_09_24_1__rbac_schema.sql;      -- platform_role 加 scope/built_in 两列 + 索引调整
source V2026_09_24_2__rbac_seed.sql;          -- 权限点目录 + 内置角色 + role_permission 关联（DELETE-then-INSERT 幂等）
source V2026_09_26_1__b3_namespace_permissions.sql;  -- B3：10 个 /namespace/** 端点权限行（漏跑则交叉校验拒启动）
```

注意：
- **V2 含 T20 修复行** `perm_member_list_delegate`（/tenant/member/list 的 platform:member:manage
  代管 ANY-of 行）——若你手上是旧版 V2 文件，admin 的成员 tab 会 403，必须用分支内最新文件。
- gateway client 的 grant_types UPDATE（移除 token-exchange）**dev 库已执行**；增量环境补一句
  （与 dump 终态一致）：
  `UPDATE oauth2_registered_client SET authorization_grant_types = 'refresh_token,authorization_code' WHERE client_id='gateway-code-client';`
- **全新环境** = 导入 `k8s_cloud_platform.sql` dump + V1 + V2 + V2026_09_26_1（dump 已是含两列与 seed 的终态，
  此时 V2 重放幂等无副作用）。

### Step 3 — 起服
先起 **platform-auth**（auth-server），确认健康后再起 **platform-api**。

### Step 4 — 启动自检
platform-api 日志必须出现：

```
[RBAC] 交叉校验通过：N 个 endpoint 全部有权限声明或豁免
```

同时**预期**看到一条 WARN（不是故障，见 §3）：

```
[RBAC] 以下权限行匹配不到任何已注册 endpoint（幽灵行...）: [/tenant/update, /user/update, /role/update]
```

## 2. 人工冒烟（auth-server 重启到本分支构建后执行）

前置：IDEA/浏览器走一次正常 PKCE 登录拿 `SESSION` cookie（DevTools → Application → Cookies →
`http://127.0.0.1:9527` 复制 `SESSION` 值）。
`<T>` = 用户 coding 所属租户 id（`platform_user_tenant` 表查），`<BAD>` = 任一不属于该用户的租户 id。

```bash
ISSUER=http://127.0.0.1:9527
CK='SESSION=<粘贴会话cookie值>'

# Step 2: 带合法 tenant_id → 期望 200，解码新 JWT data.tenantInfo.tenantId==<T>，permissions 含 tenant:* 族
curl -s -X POST "$ISSUER/oauth2/token" -H "Accept: application/json" -H "Cookie: $CK" \
  -d "grant_type=urn:coding:grant-type:session-renewal&client_id=platform-web-client&tenant_id=<T>" \
  | tee /tmp/renew.json; echo
# 解码（取 access_token 中段 base64url）：
ACCESS=$(python -c "import json;print(json.load(open('/tmp/renew.json'))['access_token'])" 2>/dev/null || grep -o '"access_token":"[^"]*"' /tmp/renew.json | cut -d'"' -f4)
python -c "import base64,json,sys;p='$ACCESS'.split('.')[1];print(json.dumps(json.loads(base64.urlsafe_b64decode(p+'===')),ensure_ascii=False,indent=1))"

# Step 4: 非法租户 → 期望 HTTP 400 + {"error":"invalid_grant","error_description":"用户不属于该租户",...}
curl -s -o /tmp/bad.json -w "HTTP %{http_code}\n" -X POST "$ISSUER/oauth2/token" \
  -H "Accept: application/json" -H "Cookie: $CK" \
  -d "grant_type=urn:coding:grant-type:session-renewal&client_id=platform-web-client&tenant_id=<BAD>"
cat /tmp/bad.json; echo

# Step 5: 不带 tenant_id → 期望 200，data.tenantInfo==null，permissions 仅平台族
curl -s -X POST "$ISSUER/oauth2/token" -H "Accept: application/json" -H "Cookie: $CK" \
  -d "grant_type=urn:coding:grant-type:session-renewal&client_id=platform-web-client" \
  | grep -o '"access_token":"[^"]*"' | cut -c16-40; echo

# Step 3: 序列化/持久化检查 —— 上面任一 200 后，查库应出现新行：
#   SELECT id, authorization_grant_type, principal_name, LEFT(attributes,120)
#     FROM oauth2_authorization
#    WHERE authorization_grant_type='urn:coding:grant-type:session-renewal'
#    ORDER BY id LIMIT 5;
#   （attributes 只应含 java.security.Principal 一项，不含 grant 对象）

# 附加回归：SPA 流程（登录 → 等 access_token 到期前 → Network 里 /oauth2/token 续期 200、用户无感；
#          租户帽态下续期 body 必须带 tenant_id，且续后仍在原租户）
```

已知边界（勿误判为缺陷）：会话过期/无 cookie 时返回 **401 空 body**（AuthorizationFilter 门槛，
非 invalid_grant JSON）；SPA `renewAccessToken()` 按 `!resp.ok` 统一走重登，行为兼容。
注：invalid_grant→400 在合入前仅有静态证据链（SAS 7.0.5 字节码级），本冒烟块是其 live 确认。

## 3. v1 已知缺口与预期现象（勿当故障处理）

| 项 | 说明 |
|---|---|
| 三条幽灵规则 WARN | `/tenant/update`、`/user/update`、`/role/update` 是有意保留的"计划端点"seed 行（UI 与后端均未交付）。每次启动会 WARN，属预期标记；将来交付端点或删行后 WARN 消失 |
| 自管轨部分降级 | 租户管理员在成员 tab：角色目录（/role/list）与用户搜索（/user/list）不可用 → 授予/回收、添加成员按钮禁用。**严禁**直接给 /user/list 补 tenant ANY-of 行（全平台用户表含 PII = 跨租户泄露）；解禁须新建服务端按租户过滤的搜索端点 |
| 同租户 K8s 动词共享 SA | 租户族角色不约束 K8s 动词（spec §10.1 roadmap：per-user SA/impersonation） |
| 无租户配额 | "申请 ns"不开放租户自助（spec §10.2） |
| gateway→k8s-server 透传长票 | 未做 exchange 缩权短票（spec §10.3）。**发布前建议核查**：是否存在调度器驱动、经 gateway 无用户上下文调 k8s-server 的路径——若有，需要服务身份，属 §10.3 领域 |
| 用户来源仅 LOCAL | LDAP/OIDC JIT 未做（spec §10.4） |
| 平台族角色粗交付 | admin 一把梭；拆分"开通专员"等为纯 seed 操作（spec §10.5） |

## 4. 回滚

1. 停 platform-api、platform-auth。
2. DB：V1 两列可留（无副作用）；V2 seed 行可整段 DELETE（按 `code LIKE 'platform:%' OR code LIKE 'tenant:%'`
   + Page 域行清理 `platform_permission` / `platform_role_permission` / `platform_role` 中 built_in 行）。
3. 代码回退到合并前 commit，重新起服。旧构建无权限表依赖，直接可跑。
