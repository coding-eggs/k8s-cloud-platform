# Docker Hub 档 + 候选跨连接聚合 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补上 Docker Hub 一档（只允许匿名），并让镜像候选**跨连接聚合** —— 用户打 `ng` 时 Harbor 与 Docker Hub 的候选同时出现、各自标出来源连接名。

**Architecture:** 三块互相咬合的改动。①把 `authType` 从只有 `BASIC` 扩出 `NONE`（匿名），顺带修掉"凭据填了就再也清不掉"的死路 —— 这是 Docker Hub 只允许匿名的前提。②`RegistryDialect` 接口加两个方法（`probeSpec` / `authTypes`），把"这一档怎么测连接"和"这一档允许哪种认证"从 `credential` 包挪回各档自己声明。③`RegistryService.suggest` 的匹配模型从"挑一条连接"改成"带 host 挑一条、不带 host 全给"，结果按连接名排序后合并，`connectionName` 随每一条候选回传。

**Tech Stack:** Spring Boot 4.1.1 / Spring Framework 7 / Java 21（虚拟线程）/ MyBatis / Jackson 3（`tools.jackson`）/ JUnit 5 + AssertJ + Mockito / Vue 3 + Element Plus + TypeScript

**Spec:** `docs/superpowers/specs/2026-10-14-registry-dockerhub-design.md`

## Global Constraints

- **零新增 SQL**。本批不动任何表、不加任何权限行。`platform_credential` 的 `auth_type` / `secret_encrypted` / `secret_fingerprint` **本来就可空** —— `V2026_10_13_3__credential.sql` 的列注释原文已写「可空是为将来 NONE 型（匿名）留位」。
- **零新增权限码**。三个只读端点继续共用 `tenant:registry:catalog`；页面码仍是 `tenant:page:registry`。本批**不新增任何端点**，所以 `PermissionCrossCheckRunner` 的 endpoint 计数应当**保持在 276 不变** —— 这是"没漏加权限行"的验收判据。
- **候选与浏览都不记审计**（S3.1 spec §8）。
- **出站 URL 只由凭据的 `endpoint`（或 dialect 自己写死的官方地址）拼**；用户输入只用于匹配和生成候选串，**绝不参与构造目标地址**。
- **`RedisJsonCache.get` 的 key 传逻辑键** —— 该类自己会加 `platform:cache:` 前缀，再带一次就是双前缀、缓存永不命中。
- 出站必须 `JdkClientHttpRequestFactory` + 显式 `HttpClient.Redirect.NEVER` + 显式超时（`SimpleClientHttpRequestFactory` 没有关重定向的 API）。
- **凭据永不回显**：响应里只有 `hasSecret` + `fingerprint`。
- 前端权限码引用 `pageCodes` / `apiCodes` 常量，**不写字面量**。
- 本仓**没有 Flyway**，`db/migration/*.sql` 一律手工重放（本批无 SQL，此条仅作提醒）。
- MySQL 注释 `--` 后必须跟空白字符（本批无 SQL，此条仅作提醒）。
- 全量测试基线：**platform-common 19 · k8s-core 210 · k8s-server 20 · platform-api 407 = 656**。本批只增不减；`k8s-core` 的 210 依赖 `WorkloadConverterStaticIpTest.java` **正被有意删在工作区**（@HEAD 即 NPE，非本批引入，**不要提交这个删除**）。

## Review Focus

以下五类输入/条件，spec 隐含要求但没有任何一条现成的测试盖住。每条都已指派到拥有该代码的任务里（见括注）：

1. **无 host 输入 + N 条连接，其中一条对端是黑洞** —— 3 秒后**只有那一条**没候选，其余连接的候选照常出现。（Task 10）
2. **输入里带 `:` 时解析出的 namespace/repo**，必须与不带 tag 时一致（`nginx:1` 要查 `library/nginx`，`bitnami/app:1` 要查 `bitnami/app`）—— 解析错就会把 tag 候选挂到别的仓库上，而且看起来像"这个 tag 不存在"。（Task 5）
3. **endpoint 写成 `https://harbor.example.com`（带 scheme）时**，用户打不带 scheme 的 `harbor.example.com/proj/ap` 必须照样能补全 —— 否则又是"配了却补不出来、且无声"。（Task 4）
4. **两条连接的候选串恰好相同**（两条连接指向同一 endpoint 是完全可能的）—— 前端下拉不能出现重复 key、不能丢行。（Task 12）
5. **匿名连接遇上要求认证的 Harbor**（`GET /v2/` 返 401）—— 「测试连接」必须**判通过**，否则每一条匿名连接一建出来就是红的。（Task 3）

---

## 文件结构

**新建（后端）**
| 文件 | 职责 |
|---|---|
| `platform-api/.../registry/ProbeSpec.java` | 测试连接的规格（URL + 该档 404 的文案） |
| `platform-api/.../registry/DockerHubDialect.java` | Docker Hub 方言：补全 / 两层浏览 / 探测规格 |
| `platform-api/.../registry/BrowseFormat.java` | `details` 列共用的展示格式化（`formatSize`），两档共用 |
| `platform-api/.../registry/RegistrySuggestExecutor.java` | 补全的并发出口（虚拟线程 + 3 秒总预算） |

**修改（后端）**
| 文件 | 改动 |
|---|---|
| `registry/RegistryDialect.java` | 接口加 `probeSpec` / `authTypes` |
| `registry/HarborDialect.java` | 段切分改用归一化 endpoint；加全局搜索分支；加 `probeSpec` / `authTypes`；`formatSize` 迁出 |
| `registry/RegistryHttpClient.java` | 无凭据时不发 `Authorization` 头 |
| `registry/RegistryTargetResolver.java` | 认 Docker Hub 的三个别名 |
| `credential/CredentialProbe.java` | 删 `registryProbeUrl`；`classifyRegistry` 加 anonymous 参数 |
| `credential/CredentialProbeClient.java` | `probeRegistry` 换成收 URL；无凭据不发头 |
| `credential/ProbeResult.java` | 加 `pass(String)` |
| `services/CredentialService.java` | `TYPES` 加 `DOCKERHUB`；`authType` 校验与存储；`test` 走分派表 |
| `services/RegistryService.java` | 匹配模型改聚合；并发；按连接名排序 |
| `models/RegistrySuggestResponse.java` | 从 `List<String>` 改成 `List<Item>` |

**修改（前端）**
| 文件 | 改动 |
|---|---|
| `platform-web/src/types.ts` | `RegistrySuggestItem`；`type` 加 `DOCKERHUB`；`authType` 加 `NONE` |
| `platform-web/src/api/index.ts` | `registryApi.suggest` 的返回类型 |
| `platform-web/src/composables/useImageSuggest.ts` | 传对象数组 |
| `platform-web/src/views/resource/WorkloadView.vue` | 下拉渲染连接名 |
| `platform-web/src/components/workload/ContainerEditor.vue` | 同上 |
| `platform-web/src/views/CredentialView.vue` | Docker Hub 选项；认证方式下拉；endpoint 预填只读；凭据框隐藏 |

---

### Task 1: `authType` 支持 `NONE`（匿名）

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/registry/RegistryDialect.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/registry/HarborDialect.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/CredentialService.java:55,71-146,193-210`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/CredentialServiceTest.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/RegistryServiceTest.java`（测试替身要补新方法）
- Test: `platform-api/src/test/java/com/coding/platformapi/registry/RegistryDialectRegistryTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `RegistryDialect.authTypes()` → `Set<String>`；`CredentialService.AUTH_NONE = "NONE"`

> **背景**：`V2026_10_13_3__credential.sql` 的三个列注释**已经预告了这件事** ——
> `secret_encrypted` 的注释原文是「v1 恒非空（BASIC 必填）；**可空是为将来 NONE 型（匿名）留位**」。
> 所以本任务**零 SQL**，且 `CredentialService.toView` 里的
> `v.setHasSecret(StringUtils.hasText(c.getSecretEncrypted()))`（第 241 行）**已经**能正确表达匿名。

- [ ] **Step 1: 写失败的测试**

在 `CredentialServiceTest` 里加（沿用该文件既有的构造方式：`new CredentialService(mapper, cipher, probeClient, dialects, ...)` —— 以文件里现有的 `create` 测试为准照抄）：

```java
@Test
void anonymousCredentialStoresNoCiphertext() {
    when(mapper.selectByName(anyString())).thenReturn(null);
    CredentialCreateRequest req = new CredentialCreateRequest();
    req.setName("public-harbor");
    req.setType("HARBOR");
    req.setEndpoint("harbor.example.com");
    req.setAuthType("NONE");

    CredentialView v = service.create(req);

    assertThat(v.getAuthType()).isEqualTo("NONE");
    assertThat(v.getHasSecret()).isFalse();
    assertThat(v.getFingerprint()).isNull();

    ArgumentCaptor<PlatformCredential> cap = ArgumentCaptor.forClass(PlatformCredential.class);
    verify(mapper).insert(cap.capture());
    assertThat(cap.getValue().getSecretEncrypted()).isNull();
}

@Test
void anonymousCredentialRejectsUsername() {
    CredentialCreateRequest req = new CredentialCreateRequest();
    req.setName("public-harbor");
    req.setType("HARBOR");
    req.setEndpoint("harbor.example.com");
    req.setAuthType("NONE");
    req.setUsername("someone");

    assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(CloudPlatformException.class)
            .hasMessageContaining("匿名");
}

@Test
void switchingToAnonymousClearsTheStoredCredential() {
    // 这条钉住 S0.2 的死路：以前「留空 = 保持不变」让凭据一旦填了就再也清不掉
    PlatformCredential old = stored("c1", "old-name", "HARBOR", "harbor.example.com", "BASIC");
    when(mapper.selectById("c1")).thenReturn(old);
    when(mapper.selectByName("old-name")).thenReturn(old);

    CredentialUpdateRequest req = new CredentialUpdateRequest();
    req.setId("c1");
    req.setName("old-name");
    req.setType("HARBOR");
    req.setEndpoint("harbor.example.com");
    req.setAuthType("NONE");

    CredentialView v = service.update(req);

    assertThat(v.getHasSecret()).isFalse();
    ArgumentCaptor<PlatformCredential> cap = ArgumentCaptor.forClass(PlatformCredential.class);
    verify(mapper).update(cap.capture());
    assertThat(cap.getValue().getSecretEncrypted()).isNull();
}

@Test
void switchingFromAnonymousToBasicRequiresBothFields() {
    PlatformCredential old = stored("c1", "n", "HARBOR", "harbor.example.com", "NONE");
    when(mapper.selectById("c1")).thenReturn(old);
    when(mapper.selectByName("n")).thenReturn(old);

    CredentialUpdateRequest req = new CredentialUpdateRequest();
    req.setId("c1"); req.setName("n"); req.setType("HARBOR");
    req.setEndpoint("harbor.example.com"); req.setAuthType("BASIC");
    req.setSecret("tok");   // 只填了 secret，没填 username

    assertThatThrownBy(() -> service.update(req))
            .isInstanceOf(CloudPlatformException.class)
            .hasMessageContaining("重新填写");
}
```

在 `RegistryDialectRegistryTest` 的护栏测试里加一条（改成遍历 `of(type).authTypes()` 断言非空）：

```java
@Test
void everyDialectDeclaresAtLeastOneAuthType() {
    for (String type : CredentialService.TYPES) {
        if ("GIT".equals(type)) continue;   // GIT 不是 dialect，见 CredentialService 的注释
        assertThat(dialects.of(type).authTypes()).isNotEmpty();
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

```bash
mvn -o -q -pl platform-api -am test -Dtest=CredentialServiceTest,RegistryDialectRegistryTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: **编译失败**（`authTypes()` 不存在 / `AUTH_NONE` 不存在），这正是本步要的红。

- [ ] **Step 3: 接口加 `authTypes()`**

`RegistryDialect.java`（加 import `java.util.Set`）：

```java
    /**
     * 本档允许的认证方式。{@code NONE} = 匿名。
     *
     * <p>声明在**各档自己**这里，是为了守住「加一档只动一处」——
     * 在 {@code CredentialService} 里维护一份「哪个类型能匿名」的手写清单就是第二个真相源，
     * 那正是 S3.1 终审删掉 {@code RegistryService.REGISTRY_TYPES} 时反对的东西。
     *
     * <p>至少要有一个值，由护栏测试钉住。
     */
    Set<String> authTypes();
```

`HarborDialect.java`：

```java
    @Override
    public Set<String> authTypes() {
        // 公共 Harbor project 是真实场景，所以匿名也允许（spec §3.7）
        return Set.of("BASIC", "NONE");
    }
```

（加 import `java.util.Set`。）

- [ ] **Step 4: `CredentialService` 的校验与存储**

在 `AUTH_BASIC` 常量旁加：

```java
    /** 匿名：没有凭据，出站请求不带 Authorization 头。 */
    static final String AUTH_NONE = "NONE";

    /**
     * GIT 的认证方式。
     *
     * <p>⚠️ GIT <b>不是</b> {@code RegistryDialect}（它不是镜像仓库），所以没法通过接口声明 ——
     * 这是全平台**唯一**一处「类型的认证方式不在 dialect 里」的例外。
     * 再加类型时先问「它是不是一个镜像仓库」，是的话就去实现 dialect。
     */
    private static final Set<String> GIT_AUTH_TYPES = Set.of(AUTH_BASIC, AUTH_NONE);
```

`validateCommon` 之后（`create` 与 `update` 都调）加：

```java
    private Set<String> allowedAuthTypes(String type) {
        if ("GIT".equals(type)) {
            return GIT_AUTH_TYPES;
        }
        // validateCommon 已经拒过未知 type，所以走到这里要么是 GIT，要么是已实现的方言
        return dialects.of(type).authTypes();
    }
```

`create` 里把旧的第 73-76 行那段必填校验**替换**成：

```java
        validateAuthOnCreate(req.getType(), req.getAuthType(), req.getUsername(), req.getSecret());
```

并在 `create` 的 `c.setAuthType(AUTH_BASIC)` 处改成 `c.setAuthType(req.getAuthType())`，把原来的
`String json = toJson(...); c.setSecretEncrypted(cipher.encrypt(json)); c.setSecretFingerprint(...)`
三行替换成：

```java
        if (AUTH_NONE.equals(req.getAuthType())) {
            // 匿名：**不写密文**，而不是写一份 username/secret 都是 null 的密文 ——
            // hasSecret 由「secret_encrypted 是否为空」直接得出，不用解密就能判断
            c.setSecretEncrypted(null);
            c.setSecretFingerprint(null);
        } else {
            String json = toJson(new CredentialSecret(req.getUsername(), req.getSecret()));
            c.setSecretEncrypted(cipher.encrypt(json));
            c.setSecretFingerprint(CredentialCipher.fingerprint(json));
        }
```

新增两个私有方法：

```java
    private void validateAuthOnCreate(String type, String authType, String username, String secret) {
        requireAllowedAuthType(type, authType);
        if (AUTH_NONE.equals(authType)) {
            if (StringUtils.hasText(username) || StringUtils.hasText(secret)) {
                throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                        "认证方式为匿名时不能填写用户名与密码");
            }
            return;
        }
        if (!StringUtils.hasText(username) || !StringUtils.hasText(secret)) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                    "认证方式为用户名密码时，用户名与密码/令牌都必须填写");
        }
    }

    private void requireAllowedAuthType(String type, String authType) {
        Set<String> allowed = allowedAuthTypes(type);
        if (authType == null || !allowed.contains(authType)) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                    "该类型支持的认证方式为 " + String.join(" / ", allowed) + "，收到：" + authType);
        }
    }
```

`update` 里把旧的第 115-142 行（`authTypeChanged` 那一段到逐字段合并那一段）**整段替换**成：

```java
        applyAuth(old, c, req);
```

并新增：

```java
    /**
     * 更新时的认证处理：**先合并出最终的用户名/密码，再校验合并结果**（而不是校验请求里的原始值）。
     *
     * <p>这样「只改了用户名、密码留空沿用旧的」与「从匿名改成 BASIC 必须重填」是同一条判据，
     * 不会各写一套。
     *
     * <p>⚠️ 改成 {@code NONE} **即清空凭据** —— S0.2 原来的「留空 = 保持不变」是「永不回显」的
     * 必然结果，但它同时意味着**凭据一旦填了就再也清不掉**（表单里拿不到旧值，"空"只能读作"不改"）。
     * 这条死路在本批被 {@code NONE} 打开。
     */
    private void applyAuth(PlatformCredential old, PlatformCredential target, CredentialUpdateRequest req) {
        requireAllowedAuthType(req.getType(), req.getAuthType());

        if (AUTH_NONE.equals(req.getAuthType())) {
            if (StringUtils.hasText(req.getUsername()) || StringUtils.hasText(req.getSecret())) {
                throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                        "认证方式为匿名时不能填写用户名与密码");
            }
            target.setSecretEncrypted(null);
            target.setSecretFingerprint(null);
            return;
        }

        boolean oldHadBasic = AUTH_BASIC.equals(old.getAuthType())
                && StringUtils.hasText(old.getSecretEncrypted());
        CredentialSecret current = oldHadBasic
                ? fromJson(cipher.decrypt(old.getSecretEncrypted()))
                : new CredentialSecret(null, null);
        String username = StringUtils.hasText(req.getUsername()) ? req.getUsername() : current.getUsername();
        String secret = StringUtils.hasText(req.getSecret()) ? req.getSecret() : current.getSecret();
        if (!StringUtils.hasText(username) || !StringUtils.hasText(secret)) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                    "认证方式为用户名密码时，用户名与密码/令牌都必须填写"
                  + (oldHadBasic ? "" : "（从匿名改过来时必须重填）"));
        }
        String json = toJson(new CredentialSecret(username, secret));
        target.setSecretEncrypted(cipher.encrypt(json));
        target.setSecretFingerprint(CredentialCipher.fingerprint(json));
    }
```

`update` 里 `c.setAuthType(AUTH_BASIC)` 改成 `c.setAuthType(req.getAuthType())`。

- [ ] **Step 5: 跑测试确认通过**

```bash
mvn -o -q -pl platform-api -am test -Dtest='CredentialServiceTest,RegistryDialectRegistryTest,RegistryServiceTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。`RegistryServiceTest` 里的匿名 `RegistryDialect` 实现要补一个 `authTypes()`（返回 `Set.of("BASIC")` 即可，它不参与本任务的断言）。

- [ ] **Step 6: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/registry/RegistryDialect.java \
        platform-api/src/main/java/com/coding/platformapi/registry/HarborDialect.java \
        platform-api/src/main/java/com/coding/platformapi/services/CredentialService.java \
        platform-api/src/test/java/com/coding/platformapi/
git commit -m "feat(credential): authType 支持 NONE（匿名），顺带修掉「填了就再也清不掉」的死路"
```

> **本任务不测 DOCKERHUB 只允许匿名** —— 那个 type 值要到 Task 6 才进 `TYPES`。

---

### Task 2: `probe` 挪进 `RegistryDialect`

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/registry/ProbeSpec.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/registry/RegistryDialect.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/registry/HarborDialect.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/credential/CredentialProbe.java:39-42`
- Modify: `platform-api/src/main/java/com/coding/platformapi/credential/CredentialProbeClient.java:70-72`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/CredentialService.java:162-172`
- Test: `platform-api/src/test/java/com/coding/platformapi/credential/CredentialProbeTest.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/CredentialServiceTest.java`

**Interfaces:**
- Consumes: `RegistryDialectRegistry.of(String)`（既有）
- Produces: `ProbeSpec(String url, String notFoundMessage)`；`RegistryDialect.probeSpec(CredentialRef)`；`CredentialProbeClient.probeRegistry(String url, String username, String secret)`

> **背景**：S3.1 spec §11 早就预告「将来若某一档的测试连接方式与 `/v2/` 不同，届时要把 probe 也挪进接口」。
> Docker Hub 就是那个"届时" —— 它的 API 站点是 `hub.docker.com`，不是它的 endpoint `docker.io`。

- [ ] **Step 1: 写失败的测试**

`CredentialProbeTest`（把原来断言 `registryProbeUrl` 的用例**删掉**，换成）：

```java
@Test
void harborProbeUrlIsV2Path() {
    CredentialRef cred = new CredentialRef("c1", "h", "HARBOR", "harbor.example.com", null, null);
    assertThat(new HarborDialect(null).probeSpec(cred).url())
            .isEqualTo("https://harbor.example.com/v2/");
}

@Test
void harborAndDockerHubGiveDifferentNotFoundMessages() {
    CredentialRef harbor = new CredentialRef("c1", "h", "HARBOR", "harbor.example.com", null, null);
    assertThat(new HarborDialect(null).probeSpec(harbor).notFoundMessage())
            .contains("不是 registry");
}
```

`CredentialServiceTest` 里加：

```java
@Test
void registryProbeGoesThroughTheDialectNotThroughSlashV2() {
    PlatformCredential c = stored("c1", "h", "HARBOR", "harbor.example.com", "BASIC");
    when(mapper.selectById("c1")).thenReturn(c);
    when(probeClient.probeRegistry(anyString(), any(), any())).thenReturn(ProbeResult.pass());

    CredentialTestRequest req = new CredentialTestRequest();
    req.setId("c1");
    service.test(req);

    ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
    verify(probeClient).probeRegistry(url.capture(), any(), any());
    assertThat(url.getValue()).isEqualTo("https://harbor.example.com/v2/");
}
```

- [ ] **Step 2: 跑测试确认它失败**

```bash
mvn -o -q -pl platform-api -am test -Dtest='CredentialProbeTest,CredentialServiceTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败（`probeSpec` / `ProbeSpec` 不存在）。

- [ ] **Step 3: 新建 `ProbeSpec`**

`platform-api/src/main/java/com/coding/platformapi/registry/ProbeSpec.java`：

```java
package com.coding.platformapi.registry;

/**
 * 一次「测试连接」的规格。各档自己给，因为两件事都是方言相关的：
 * <ul>
 *   <li><b>URL</b> —— Docker Hub 的 API 站点是 {@code hub.docker.com}，**不是**它的 endpoint
 *       {@code docker.io}；Harbor 才是 {@code <endpoint>/v2/}。</li>
 *   <li><b>404 的含义</b> —— 对 Harbor 是「这个地址不是个 registry」，
 *       对 Docker Hub 是「公共 API 被中间件改写了」。同一句写死会把管理员引去查一个没问题的地址。</li>
 * </ul>
 *
 * <p>设计见 docs/superpowers/specs/2026-10-14-registry-dockerhub-design.md §4。
 */
public record ProbeSpec(String url, String notFoundMessage) {
}
```

- [ ] **Step 4: 接口加方法 + `HarborDialect` 实现**

`RegistryDialect.java`：

```java
    /**
     * 「测试连接」的规格。见 {@link ProbeSpec}。
     *
     * <p>⚠️ 出站那一半（超时、{@code Redirect.NEVER}、错误归因）**仍在
     * {@code CredentialProbeClient} 一处**，各档只给 URL 与文案 —— 那四条安全要求散到各档会漏。
     */
    ProbeSpec probeSpec(CredentialRef cred);
```

`HarborDialect.java`：

```java
    @Override
    public ProbeSpec probeSpec(CredentialRef cred) {
        // 从 CredentialProbe.registryProbeUrl 原样搬过来：`/v2/` 是 OCI 规范的一部分，所有 registry 都实现
        return new ProbeSpec(base(cred) + "/v2/",
                "地址不对：该地址不是 registry（/v2/ 不存在）");
    }
```

- [ ] **Step 5: 删 `CredentialProbe.registryProbeUrl` 并改 `CredentialProbeClient` 签名**

`CredentialProbe.java`：删掉第 39-42 行的 `registryProbeUrl`（连带其 javadoc）。
`registryProbeUrl` 的 import 若有别处使用会由编译器报出来。

`CredentialProbeClient.java` 第 70-72 行：

```java
    /**
     * 探测 registry。**URL 由调用方（各档的 {@code probeSpec}）给** ——
     * 不同档的 API 站点不一样，`<endpoint>/v2/` 只对 Harbor 成立。
     */
    public ProbeResult probeRegistry(String url, String username, String secret) {
        return probe(url, username, secret, false);
    }
```

- [ ] **Step 6: `CredentialService.test` 走分派表**

把第 162-172 行替换成：

```java
        ProbeResult result;
        if ("GIT".equals(c.getType())) {
            if (!StringUtils.hasText(req.getProbeUrl())) {
                throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                        "探测 Git 凭据需要给出一个用于测试的仓库地址"
                      + "（凭据是按 host 配的，能不能用取决于具体仓库的权限）");
            }
            result = probeClient.probeGit(req.getProbeUrl(), s.getUsername(), s.getSecret());
        } else {
            // ⚠️ 走分派表而不是自己判 type —— 手写清单是第二个真相源
            CredentialRef ref = new CredentialRef(c.getId(), c.getName(), c.getType(),
                    c.getEndpoint(), s.getUsername(), s.getSecret());
            result = probeClient.probeRegistry(
                    dialects.of(c.getType()).probeSpec(ref).url(), s.getUsername(), s.getSecret());
        }
```

（在 `CredentialService` 里注入 `RegistryDialectRegistry dialects`，构造器加一个参数 —— 若已有则复用。）

- [ ] **Step 7: 跑测试确认通过**

```bash
mvn -o -q -pl platform-api -am test -Dtest='CredentialProbeTest,CredentialProbeClientTest,CredentialServiceTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。

- [ ] **Step 8: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/ platform-api/src/test/java/com/coding/platformapi/
git commit -m "refactor(registry): probe 挪进 RegistryDialect 接口（S3.1 spec §11 预告的那件事）"
```

---

### Task 3: 出站层支持匿名

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/credential/ProbeResult.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/credential/CredentialProbe.java:87-98`
- Modify: `platform-api/src/main/java/com/coding/platformapi/credential/CredentialProbeClient.java:74-100`
- Modify: `platform-api/src/main/java/com/coding/platformapi/registry/RegistryHttpClient.java:62-100`
- Test: `platform-api/src/test/java/com/coding/platformapi/credential/CredentialProbeTest.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/credential/CredentialProbeClientTest.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/registry/RegistryHttpClientTest.java`

**Interfaces:**
- Consumes: Task 1 的 `AUTH_NONE` 语义（本任务只看"凭据字段空不空"，不看 authType）
- Produces: `ProbeResult.pass(String message)`；`CredentialProbe.classifyRegistry(int status, boolean anonymous)`

- [ ] **Step 1: 写失败的测试**

`CredentialProbeTest`：

```java
@Test
void anonymousRegistryProbeTreatsUnauthorizedAsReachable() {
    // OCI 规范：任何 registry 的 GET /v2/ 未认证时返 401 是**正常行为**，
    // 它是「这是个 registry 且它要认证」，不是「你的凭据错了」。
    // 不这么判的话，每一条匿名连接一建出来就是红的。
    ProbeResult r = CredentialProbe.classifyRegistry(401, true);
    assertThat(r.ok()).isTrue();
}

@Test
void basicRegistryProbeStillFailsOnUnauthorized() {
    ProbeResult r = CredentialProbe.classifyRegistry(401, false);
    assertThat(r.ok()).isFalse();
    assertThat(r.message()).contains("凭据不通过");
}

@Test
void anonymousRegistryProbeStillFailsOnForbidden() {
    // 403 是真实的拒绝（IP 被封等），匿名与 BASIC 一样要报
    assertThat(CredentialProbe.classifyRegistry(403, true).ok()).isFalse();
}
```

`CredentialProbeClientTest`（沿用该文件既有的 `MockRestServiceServer` 搭法）：

```java
@Test
void anonymousProbeSendsNoAuthorizationHeader() {
    server.expect(requestTo("https://harbor.example.com/v2/"))
            .andExpect(headerDoesNotExist("Authorization"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    ProbeResult r = client.probeRegistry("https://harbor.example.com/v2/", null, null);

    assertThat(r.ok()).isTrue();
}

@Test
void basicProbeStillSendsTheHeader() {
    server.expect(requestTo("https://harbor.example.com/v2/"))
            .andExpect(header("Authorization", startsWith("Basic ")))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    assertThat(client.probeRegistry("https://harbor.example.com/v2/", "u", "p").ok()).isTrue();
}
```

`RegistryHttpClientTest`：

```java
@Test
void anonymousRefSendsNoAuthorizationHeader() {
    server.expect(requestTo("https://harbor.example.com/api/v2.0/search?q=ng"))
            .andExpect(headerDoesNotExist("Authorization"))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    CredentialRef anon = new CredentialRef("c1", "h", "HARBOR", "harbor.example.com", null, null);
    assertThat(client.get(anon, "https://harbor.example.com/api/v2.0/search?q=ng")).isEqualTo("[]");
}
```

- [ ] **Step 2: 跑测试确认它失败**

```bash
mvn -o -q -pl platform-api -am test -Dtest='CredentialProbeTest,CredentialProbeClientTest,RegistryHttpClientTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL —— 断言 `Authorization` 头不存在的那两条会红（现在发的是 `Basic Og==`，一个只有冒号的空 Basic）。

- [ ] **Step 3: `ProbeResult` 加带文案的 `pass`**

```java
    public static ProbeResult pass(String message) {
        return new ProbeResult(true, message, null);
    }
```

- [ ] **Step 4: `CredentialProbe.classifyRegistry` 加 anonymous 参数**

```java
    /**
     * registry 探测的结果判定。
     *
     * @param anonymous 该连接是不是匿名的。**401 的读法随之不同**：
     *                  OCI 规范下任何 registry 的 {@code GET /v2/} 未认证时返 401 是正常行为，
     *                  对匿名连接它是「这是个 registry 且它要认证」而不是失败（spec §3.7）；
     *                  403 是真实的拒绝（IP 被封等），两种都报。
     */
    public static ProbeResult classifyRegistry(int status, boolean anonymous) {
        if (status == 200) {
            return ProbeResult.pass();
        }
        if (status == 401) {
            return anonymous
                    ? ProbeResult.pass("可访问（该 registry 要求认证，匿名仍可拉取公开仓库）")
                    : ProbeResult.fail("凭据不通过，或该凭据无权访问此 registry");
        }
        if (status == 403) {
            return ProbeResult.fail(anonymous
                    ? "该 registry 拒绝了这个来源的匿名访问"
                    : "凭据不通过，或该凭据无权访问此 registry");
        }
        if (status == 404) {
            return ProbeResult.fail("地址不对：该地址不是 registry（/v2/ 不存在）");
        }
        return ProbeResult.fail("对端返回 " + status);
    }
```

⚠️ 404 那句**保留**，因为 `CredentialProbeClient` 现在还不知道是从哪一档调来的。Task 6 会把 `notFoundMessage` 接进来 —— 在 Task 6 之前 Docker Hub 档还不可用，所以不会有人看到错的文案。

- [ ] **Step 5: 两个出站类都不发空 Basic 头**

`CredentialProbeClient.probe`：

```java
    private ProbeResult probe(String url, String username, String secret, boolean git) {
        try {
            RestClient.RequestHeadersSpec<?> spec = restClient.get().uri(java.net.URI.create(url));
            if (StringUtils.hasText(username) || StringUtils.hasText(secret)) {
                spec.header(HttpHeaders.AUTHORIZATION, basic(username, secret));
            }
            boolean anonymous = !StringUtils.hasText(username) && !StringUtils.hasText(secret);
            ProbeResult response = spec.exchange((request, res) -> {
                        String body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        String location = res.getHeaders().getFirst(HttpHeaders.LOCATION);
                        int status = res.getStatusCode().value();
                        return git
                                ? CredentialProbe.classifyGit(status, body, location)
                                : CredentialProbe.classifyRegistry(status, anonymous);
                    });
            return response != null ? response : ProbeResult.fail("对端返回空响应");
        } catch (IllegalArgumentException e) {
            // ...（以下不动）
```

（加 import `org.springframework.util.StringUtils`；`spec.header(...)` 的返回值可以忽略 —— 它就地修改。）

`RegistryHttpClient.get` 同样处理：

```java
    public String get(CredentialRef cred, String url) {
        try {
            RestClient.RequestHeadersSpec<?> spec = restClient.get().uri(URI.create(url));
            if (hasCredential(cred)) {
                spec.header(HttpHeaders.AUTHORIZATION, basic(cred));
            }
            return spec.retrieve()
                    // ...（以下 onStatus 链不动）
```

并加：

```java
    /** 匿名的 ref（username 与 secret 都空）不带 Authorization 头 —— 否则会发出 `Basic Og==`，一个只有冒号的空 Basic。 */
    private static boolean hasCredential(CredentialRef cred) {
        return StringUtils.hasText(cred.username()) || StringUtils.hasText(cred.secret());
    }
```

- [ ] **Step 6: 跑测试确认通过**

```bash
mvn -o -q -pl platform-api -am test -Dtest='CredentialProbeTest,CredentialProbeClientTest,RegistryHttpClientTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/ platform-api/src/test/java/com/coding/platformapi/
git commit -m "feat(credential): 出站层支持匿名（不发空 Basic 头；匿名 401 判可达）"
```

---

### Task 4: `HarborDialect` 段切分改用归一化 endpoint（**修一个现存 bug**）

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/registry/HarborDialect.java:207-234,282-284`
- Test: `platform-api/src/test/java/com/coding/platformapi/registry/HarborDialectTest.java`

**Interfaces:**
- Consumes: `RegistryTargetResolver.normalizeEndpoint(String)`（既有）
- Produces: `segmentsOf` / `searchTermOf` / `host` 的行为变化（签名不变）

> **这是 bug，不是重构。** 终审那次给 `RegistryTargetResolver` 加了 `normalizeEndpoint`，
> 但 `HarborDialect.segmentsOf` 仍然拿**原始 endpoint** 做 `startsWith`。
> 于是 endpoint 填成 `https://harbor.example.com`（GIT 的 placeholder 正是 `https://gitlab.example.com`，
> 切类型时极易残留）时：**连接匹配得上、`segmentsOf` 取不到段 → 补全静默返回空**，而浏览页一切正常。
> 与终审抓到的那条 Critical（tag 补全恒不工作）是同一形状。

- [ ] **Step 1: 写失败的测试**

`HarborDialectTest`：

```java
@Test
void suggestWorksWhenEndpointCarriesAScheme() {
    // 匹配层已经做了归一化（RegistryTargetResolver.normalizeEndpoint），
    // 段切分这一层以前没有 —— 结果是「配了却补不出来，且无声」
    assertThat(HarborDialect.segmentsOf("harbor.example.com/proj/app", "https://harbor.example.com"))
            .containsExactly("proj", "app");
}

@Test
void suggestWorksWhenEndpointHasATrailingSlash() {
    assertThat(HarborDialect.segmentsOf("harbor.example.com/proj/app", "harbor.example.com/"))
            .containsExactly("proj", "app");
}

@Test
void suggestWorksWhenEndpointIsUpperCase() {
    assertThat(HarborDialect.segmentsOf("harbor.example.com/proj/app", "HARBOR.example.com"))
            .containsExactly("proj", "app");
}

@Test
void searchTermStillRejectsTheWrongHost() {
    assertThat(HarborDialect.searchTermOf("other.example.com/ng", "harbor.example.com")).isNull();
}
```

- [ ] **Step 2: 跑测试确认它失败**

```bash
mvn -o -q -pl platform-api -am test -Dtest=HarborDialectTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 前三条 FAIL（返回空 list），第四条 PASS。

- [ ] **Step 3: 归一化**

`HarborDialect.segmentsOf`：

```java
    /**
     * 去掉 host 之后按 {@code /} 切的段。{@code harbor.example.com/proj/app} → {@code [proj, app]}。
     *
     * <p>⚠️ **两边都要归一化**：endpoint 是自由文本、无格式校验，而 GIT 档的 placeholder 正是
     * {@code https://gitlab.example.com} —— 切类型时极易把 `https://`、尾斜杠、大写带进来。
     * 不归一化的话，连接照样匹配得上（{@link RegistryTargetResolver} 那边归一化了），
     * 但这里取不到段 → **补全静默返回空**，用户看不出任何线索。
     */
    public static List<String> segmentsOf(String imagePrefix, String endpoint) {
        String s = imagePrefix == null ? "" : imagePrefix.trim();
        String host = RegistryTargetResolver.normalizeEndpoint(endpoint);
        if (host.isEmpty() || !s.toLowerCase(Locale.ROOT).startsWith(host)) {
            return List.of();
        }
        String rest = s.substring(host.length());
        while (rest.startsWith("/") || rest.startsWith(":")) {
            rest = rest.substring(1);
        }
        if (rest.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(rest.split("/")).filter(x -> !x.isEmpty()).toList();
    }
```

`HarborDialect.host`：

```java
    /** 候选串里的 host —— 与匹配层同一个口径（去 scheme、去尾斜杠、小写）。 */
    private static String host(CredentialRef c) {
        return RegistryTargetResolver.normalizeEndpoint(c.endpoint());
    }
```

（加 import `java.util.Locale`。）

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -o -q -pl platform-api -am test -Dtest='HarborDialectTest,RegistryHttpClientTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。**注意**：`host()` 现在返回小写，若既有用例断言了带大写的候选串，改成小写（小写才是对的一侧 —— 它与匹配层同口径）。

- [ ] **Step 5: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/registry/HarborDialect.java platform-api/src/test/java/com/coding/platformapi/registry/HarborDialectTest.java
git commit -m "fix(registry): Harbor 段切分改用归一化 endpoint —— endpoint 带 scheme 时补全静默返回空"
```

---

### Task 5: `DockerHubDialect` 的纯函数

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/registry/BrowseFormat.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/registry/DockerHubDialect.java`（本任务只写纯函数部分）
- Modify: `platform-api/src/main/java/com/coding/platformapi/registry/HarborDialect.java`（`formatSize` 迁出）
- Test: `platform-api/src/test/java/com/coding/platformapi/registry/DockerHubDialectTest.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/registry/HarborDialectTest.java`（`formatSize` 的断言迁到新测试）

**Interfaces:**
- Consumes: `RegistryHttpClient`（构造器注入，本任务不出站）
- Produces:
  - `DockerHubDialect.type()` → `"DOCKERHUB"`
  - `DockerHubDialect.hostPrefixOf(String)` → `String`（`""` 或 `"docker.io/"`）
  - `DockerHubDialect.namespaceOf(String rest)` / `repoOf(String rest)` → `String`
  - `DockerHubDialect.stripLibrary(String repoName, String hostPrefix)` → `String`
  - `DockerHubDialect.searchUrl(String keyword)` / `repositoriesUrl(String ns)` / `tagsUrl(String ns, String repo)` → `String`
  - `DockerHubDialect.parseSearchHits(String json)` → `List<String>`
  - `DockerHubDialect.parseRepositoryNames(String json)` → `List<String>`
  - `DockerHubDialect.parseTags(String json)` → `List<BrowseFormat.TagInfo>`
  - `BrowseFormat.formatSize(long)` → `String`；`BrowseFormat.TagInfo(String name, String pushedAt, long size, String digest)`

> **为什么把 `formatSize` 抽出来**：两档的 `details` 列名一样（推送时间 / 大小 / digest），
> 格式化各写一份就会漂成"同一个界面里两套写法"。抽的是**纯展示格式化**，
> 不是用户否决过的"k8s 资源公共基类"那类业务抽象。

- [ ] **Step 1: 写失败的测试**

`DockerHubDialectTest`：

```java
class DockerHubDialectTest {

    @Test
    void hostPrefixIsEmptyWhenUserTypedNoHost() {
        assertThat(DockerHubDialect.hostPrefixOf("ng")).isEmpty();
        assertThat(DockerHubDialect.hostPrefixOf("myteam/ng")).isEmpty();
    }

    @Test
    void hostPrefixKeepsWhicheverAliasTheUserTyped() {
        assertThat(DockerHubDialect.hostPrefixOf("docker.io/ng")).isEqualTo("docker.io/");
        assertThat(DockerHubDialect.hostPrefixOf("registry-1.docker.io/ng"))
                .isEqualTo("registry-1.docker.io/");
    }

    @Test
    void singleSegmentFallsIntoTheOfficialLibrary() {
        assertThat(DockerHubDialect.namespaceOf("nginx")).isEqualTo("library");
        assertThat(DockerHubDialect.repoOf("nginx")).isEqualTo("nginx");
    }

    @Test
    void explicitNamespaceIsUsedAsIs() {
        assertThat(DockerHubDialect.namespaceOf("myteam/app")).isEqualTo("myteam");
        assertThat(DockerHubDialect.repoOf("myteam/app")).isEqualTo("app");
    }

    @Test
    void libraryPrefixIsStrippedOnlyWhenTheUserTypedNoHost() {
        assertThat(DockerHubDialect.stripLibrary("library/nginx", "")).isEqualTo("nginx");
        assertThat(DockerHubDialect.stripLibrary("library/nginx", "docker.io/"))
                .isEqualTo("library/nginx");
        assertThat(DockerHubDialect.stripLibrary("myteam/nginx", "")).isEqualTo("myteam/nginx");
    }

    @Test
    void searchUrlCarriesTheKeywordAndPageSize() {
        assertThat(DockerHubDialect.searchUrl("ng"))
                .isEqualTo("https://hub.docker.com/v2/search/repositories/?query=ng&page_size=25");
    }

    @Test
    void tagsUrlHasNoDoubleSlashBetweenNamespaceAndRepo() {
        assertThat(DockerHubDialect.tagsUrl("library", "nginx"))
                .isEqualTo("https://hub.docker.com/v2/repositories/library/nginx/tags?page_size=100");
    }

    @Test
    void repositoriesUrlEndsWithSlashBeforeTheQuery() {
        assertThat(DockerHubDialect.repositoriesUrl("library"))
                .isEqualTo("https://hub.docker.com/v2/repositories/library/?page_size=100");
    }

    @Test
    void parsesSearchHitsAndStripsNothingHere() {
        String json = """
                {"count":2,"results":[
                  {"repo_name":"library/nginx"},
                  {"repo_name":"myteam/nginx-agent"}]}""";
        assertThat(DockerHubDialect.parseSearchHits(json))
                .containsExactly("library/nginx", "myteam/nginx-agent");
    }

    @Test
    void parsesTagsWithAllThreeDetailColumns() {
        String json = """
                {"results":[{"name":"1.27","last_updated":"2026-10-13T21:04:00Z",
                             "full_size":86400000,"digest":"sha256:9f2c"}]}""";
        List<BrowseFormat.TagInfo> tags = DockerHubDialect.parseTags(json);
        assertThat(tags).hasSize(1);
        assertThat(tags.get(0).name()).isEqualTo("1.27");
        assertThat(tags.get(0).size()).isEqualTo(86400000L);
        assertThat(tags.get(0).digest()).isEqualTo("sha256:9f2c");
    }

    @Test
    void malformedJsonYieldsEmptyNotAnException() {
        // 站点接口没有 SLA（分析文档 §7.5 前提 2），响应形状随时可能变 —— 不能让它把补全打挂
        assertThat(DockerHubDialect.parseSearchHits("<html>gateway</html>")).isEmpty();
        assertThat(DockerHubDialect.parseTags("not json")).isEmpty();
        assertThat(DockerHubDialect.parseRepositoryNames("")).isEmpty();
    }
}
```

`BrowseFormatTest`（新文件，承接从 `HarborDialectTest` 迁过来的 `formatSize` 用例）：

```java
@Test
void formatsSizesForTheDetailsColumn() {
    assertThat(BrowseFormat.formatSize(0)).isEqualTo("—");
    assertThat(BrowseFormat.formatSize(512)).isEqualTo("512 B");
    assertThat(BrowseFormat.formatSize(2048)).isEqualTo("2.0 KB");
    assertThat(BrowseFormat.formatSize(86400000L)).isEqualTo("82.4 MB");
}
```

- [ ] **Step 2: 跑测试确认它失败**

```bash
mvn -o -q -pl platform-api -am test -Dtest='DockerHubDialectTest,BrowseFormatTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败（两个类都不存在）。

- [ ] **Step 3: 建 `BrowseFormat`**

`platform-api/src/main/java/com/coding/platformapi/registry/BrowseFormat.java`：

```java
package com.coding.platformapi.registry;

/**
 * 浏览结果里 {@code details} 列的展示格式化。两档共用。
 *
 * <p>抽出来不是为了抽象，是为了**同一个界面上只有一套写法** —— 列名两档是一样的
 * （推送时间 / 大小 / digest），各写一份迟早会漂。
 */
public final class BrowseFormat {

    private BrowseFormat() {
    }

    /** 字节数转人类可读。{@code <= 0} 给 {@code —}（不造一个假的 0 B）。 */
    public static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "—";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        return String.format("%.1f MB", kb / 1024.0);
    }

    /** 一个 tag 行（响应映射的中间形态，便于单测）。 */
    public record TagInfo(String name, String pushedAt, long size, String digest) {
    }
}
```

`HarborDialect` 里删掉 `formatSize` 与 `TagInfo`，改 import `BrowseFormat.TagInfo`（或全限定引用）。
`HarborDialectTest` 里 `formatSize` 的用例删掉（已在 `BrowseFormatTest`）。

- [ ] **Step 4: 建 `DockerHubDialect` 的纯函数部分**

`platform-api/src/main/java/com/coding/platformapi/registry/DockerHubDialect.java`：

```java
package com.coding.platformapi.registry;

import com.coding.platformapi.registry.BrowseFormat.TagInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Docker Hub 方言。设计见 docs/superpowers/specs/2026-10-14-registry-dockerhub-design.md §3。
 *
 * <h3>两个与其他档不同的地方</h3>
 * <ol>
 *   <li><b>只允许匿名</b>（{@link #authTypes()}）—— 这一档的定位是「公共镜像的来源」，
 *       私有镜像该放自建 Harbor（用户裁定，spec §3.6）。所以它也没有 username，
 *       浏览第一层只有 {@code library} 一个入口。</li>
 *   <li><b>出站地址是写死的 {@code hub.docker.com}，不是 endpoint</b> —— Docker Hub 的 API 站点
 *       与它的 registry 名（{@code docker.io}）不是一回事。endpoint 只用于匹配。
 *       这不是绕过「出站地址不来自用户输入」那条边界，正是它的用法：用户连填都填不了。</li>
 * </ol>
 *
 * <p>⚠️ <b>走的是 registry 站点自身用的接口</b>（{@code hub.docker.com/v2/…}），**没有官方 SLA**
 * （分析文档 §7.5 前提 2）。选它是因为官方 OCI 路径 {@code registry-1.docker.io/v2/…/tags/list}
 * **没有搜索接口**（补全根本做不出来），且 {@code tags/list} 只给 tag 名，
 * 浏览页要的三列得逐 tag 再取 manifest（N+1）。
 * 代价就是：**必须缓存 + 静默降级**（spec §2.4 / §3.2）。
 */
@Slf4j
@Component
public class DockerHubDialect implements RegistryDialect {

    static final int SEARCH_PAGE_SIZE = 25;

    static final int PAGE_SIZE = 100;

    /** Docker 官方库的命名空间。单段输入按 Docker 的字面语义归到这里。 */
    static final String LIBRARY = "library";

    /** 这一档只允许匿名（spec §3.6）。 */
    private static final Set<String> AUTH_TYPES = Set.of("NONE");

    private static final String API = "https://hub.docker.com/v2";

    /**
     * 指向同一个 registry 的三种写法。用户打哪种都算 ——
     * 做候选串前缀时**保留用户打的那个**，不替换成 docker.io。
     */
    static final List<String> HOST_ALIASES =
            List.of("docker.io", "index.docker.io", "registry-1.docker.io");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RegistryHttpClient http;

    public DockerHubDialect(RegistryHttpClient http) {
        this.http = http;
    }

    @Override
    public String type() {
        return "DOCKERHUB";
    }

    @Override
    public Set<String> authTypes() {
        return AUTH_TYPES;
    }

    // ==================== 输入切分（纯） ====================

    /**
     * 用户打的 host 前缀（**含末尾斜杠**），没打 host 时返回空串。
     *
     * <p>保留用户写的那个别名：他打 {@code registry-1.docker.io/ng} 就还他
     * {@code registry-1.docker.io/nginx}，不擅自改写成 {@code docker.io/nginx}。
     */
    public static String hostPrefixOf(String imagePrefix) {
        String p = imagePrefix == null ? "" : imagePrefix.trim();
        int slash = p.indexOf('/');
        if (slash < 0) {
            return "";
        }
        return HOST_ALIASES.contains(p.substring(0, slash).toLowerCase(Locale.ROOT))
                ? p.substring(0, slash + 1)
                : "";
    }

    /**
     * 无 host 的单段输入按 **Docker 的字面语义**归到官方库 ——
     * {@code nginx} 就是 {@code library/nginx}，与 {@code docker pull nginx} 一致。
     */
    public static String namespaceOf(String rest) {
        int slash = rest.indexOf('/');
        return slash < 0 ? LIBRARY : rest.substring(0, slash);
    }

    public static String repoOf(String rest) {
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(slash + 1);
    }

    /**
     * 无 host 输入时剥掉 {@code library/} —— {@code nginx} 与 {@code library/nginx} 是同一个镜像，
     * 填短的更常见。用户显式打了 host 前缀就保留（他既然写全了，就别动他写的）。
     */
    public static String stripLibrary(String repoName, String hostPrefix) {
        if (!hostPrefix.isEmpty()) {
            return repoName;
        }
        String prefix = LIBRARY + "/";
        return repoName.startsWith(prefix) ? repoName.substring(prefix.length()) : repoName;
    }

    // ==================== URL 构造（纯） ====================

    public static String searchUrl(String keyword) {
        return API + "/search/repositories/?query="
                + UriUtils.encodeQueryParam(keyword, StandardCharsets.UTF_8)
                + "&page_size=" + SEARCH_PAGE_SIZE;
    }

    public static String repositoriesUrl(String namespace) {
        return API + "/repositories/" + enc(namespace) + "/?page_size=" + PAGE_SIZE;
    }

    public static String tagsUrl(String namespace, String repo) {
        return API + "/repositories/" + enc(namespace) + "/" + enc(repo)
                + "/tags?page_size=" + PAGE_SIZE;
    }

    private static String enc(String s) {
        return UriUtils.encodePathSegment(s, StandardCharsets.UTF_8);
    }

    // ==================== 响应映射（纯） ====================

    /**
     * 三个接口的响应都是 {@code {"count":N,"results":[…]}} —— 所以共用一层取 {@code results}。
     * 任何一个字段缺失/形状变了都**按空处理**：站点接口没有 SLA，不能让它把补全打挂。
     */
    private static List<Row> rows(String json) {
        try {
            Root root = JSON.readValue(json, Root.class);
            return root.results == null ? List.of() : root.results;
        } catch (Exception e) {
            log.warn("解析 Docker Hub 响应失败，按空处理", e);
            return List.of();
        }
    }

    /** 搜索命中，形如 {@code library/nginx} 或 {@code myteam/app}。 */
    public static List<String> parseSearchHits(String json) {
        return rows(json).stream().map(r -> r.repo_name).filter(Objects::nonNull).toList();
    }

    public static List<String> parseRepositoryNames(String json) {
        return rows(json).stream().map(r -> r.name).filter(Objects::nonNull).toList();
    }

    public static List<TagInfo> parseTags(String json) {
        return rows(json).stream()
                .filter(r -> r.name != null)
                .map(r -> new TagInfo(r.name, r.last_updated, r.full_size, r.digest))
                .toList();
    }

    @lombok.Data static class Root {
        private List<Row> results;
    }

    /** 三个接口的行字段并集 —— 各投影取自己那几个。 */
    @lombok.Data static class Row {
        private String name;
        private String repo_name;
        private String last_updated;
        private long full_size;
        private String digest;
    }
}
```

⚠️ 本任务**先不实现** `suggest` / `browse` / `probeSpec` —— 那会让类编译不过。所以本步先给三个方法留最小可编译的实现（Task 6 替换）：

```java
    // ⚠️ Task 6 替换成真实现。本任务只交付纯函数，先让它能编译。
    @Override public List<String> suggest(CredentialRef cred, String imagePrefix) { return List.of(); }
    @Override public BrowseResult browse(CredentialRef cred, List<String> path) { return new BrowseResult(List.of(), List.of()); }
    @Override public ProbeSpec probeSpec(CredentialRef cred) { return new ProbeSpec(API, ""); }
```

- [ ] **Step 5: 跑测试确认通过**

```bash
mvn -o -q -pl platform-api -am test -Dtest='DockerHubDialectTest,BrowseFormatTest,HarborDialectTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/registry/ platform-api/src/test/java/com/coding/platformapi/registry/
git commit -m "feat(registry): DockerHubDialect 纯函数（URL / 解析 / 候选串前缀规则）"
```

---

### Task 6: `DockerHubDialect` 接线 + `TYPES` 加 `DOCKERHUB`

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/registry/DockerHubDialect.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/CredentialService.java:55`
- Modify: `platform-api/src/main/java/com/coding/platformapi/credential/CredentialProbeClient.java`（把 `notFoundMessage` 接进来）
- Test: `platform-api/src/test/java/com/coding/platformapi/registry/DockerHubDialectTest.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/CredentialServiceTest.java`

**Interfaces:**
- Consumes: Task 5 的全部纯函数；`RegistryHttpClient.get(CredentialRef, String)`；Task 3 的 `classifyRegistry(int, boolean)`
- Produces: `DockerHubDialect.suggest/browse/probeSpec` 的真实现；`CredentialService.TYPES` 含 `DOCKERHUB`

- [ ] **Step 1: 写失败的测试**

`DockerHubDialectTest` 里加（该文件的 `client` 用 `MockRestServiceServer` 搭，照 `RegistryHttpClientTest` 的搭法）：

```java
@Test
void suggestOnASingleSegmentSearchesAndStripsTheLibraryPrefix() {
    server.expect(requestTo("https://hub.docker.com/v2/search/repositories/?query=ng&page_size=25"))
            .andRespond(withSuccess("""
                    {"results":[{"repo_name":"library/nginx"},{"repo_name":"myteam/nginx-agent"}]}""",
                    MediaType.APPLICATION_JSON));

    assertThat(dialect.suggest(anonCred(), "ng"))
            .containsExactly("nginx", "myteam/nginx-agent");
}

@Test
void suggestKeepsTheHostPrefixTheUserTyped() {
    server.expect(requestTo("https://hub.docker.com/v2/search/repositories/?query=ng&page_size=25"))
            .andRespond(withSuccess("""
                    {"results":[{"repo_name":"library/nginx"}]}""", MediaType.APPLICATION_JSON));

    assertThat(dialect.suggest(anonCred(), "docker.io/ng"))
            .containsExactly("docker.io/library/nginx");
}

@Test
void suggestOnAHostOnlyInputMakesNoRequest() {
    // 只打了 host：把整个 registry 的候选拉出来既慢又没意义
    assertThat(dialect.suggest(anonCred(), "docker.io")).isEmpty();
    assertThat(dialect.suggest(anonCred(), "docker.io/")).isEmpty();
}

@Test
void tagCompletionParsesNamespaceAndRepoConsistentlyWithTheNonTagPath() {
    // ⭐ Review Focus #2：`nginx:1` 要查 library/nginx，`bitnami/app:1` 要查 bitnami/app。
    // 解析错就会把 tag 候选挂到别的仓库上，而现象只是"这个 tag 不存在"
    server.expect(requestTo("https://hub.docker.com/v2/repositories/library/nginx/tags?page_size=100"))
            .andRespond(withSuccess("""
                    {"results":[{"name":"1.27","last_updated":"2026-10-13T21:04:00Z",
                                 "full_size":86400000,"digest":"sha256:9f2c"},
                                {"name":"1.26","full_size":1}]}""", MediaType.APPLICATION_JSON));
    assertThat(dialect.suggest(anonCred(), "nginx:1")).containsExactly("nginx:1.27", "nginx:1.26");

    server.reset();
    server.expect(requestTo("https://hub.docker.com/v2/repositories/bitnami/app/tags?page_size=100"))
            .andRespond(withSuccess("""{"results":[{"name":"1.0","full_size":1}]}""",
                    MediaType.APPLICATION_JSON));
    assertThat(dialect.suggest(anonCred(), "bitnami/app:1")).containsExactly("bitnami/app:1.0");
}

@Test
void browseFirstLevelIsOnlyTheOfficialLibrary() {
    // 该档只允许匿名 → 没有 username 可以列，所以第一层只有一个入口（spec §3.3）
    BrowseResult r = dialect.browse(anonCred(), List.of());
    assertThat(r.children()).extracting(BrowseResult.Node::name).containsExactly("library");
    assertThat(r.children().get(0).hasChildren()).isTrue();
}

@Test
void browseTagLeafCopyTextStripsLibraryToo() {
    server.expect(requestTo("https://hub.docker.com/v2/repositories/library/nginx/tags?page_size=100"))
            .andRespond(withSuccess("""
                    {"results":[{"name":"1.27","full_size":86400000,"digest":"sha256:9f2c"}]}""",
                    MediaType.APPLICATION_JSON));

    BrowseResult r = dialect.browse(anonCred(), List.of("library", "nginx"));
    assertThat(r.children().get(0).copyText()).isEqualTo("nginx:1.27");
    assertThat(r.children().get(0).details()).extracting(BrowseResult.Detail::label)
            .containsExactly("推送时间", "大小", "digest");
}

@Test
void probeTargetsThePublicApiNotTheEndpoint() {
    // ⭐ 这条钉住「出站地址不来自 endpoint」：endpoint 是 docker.io，API 站是 hub.docker.com
    ProbeSpec spec = dialect.probeSpec(anonCred());
    assertThat(spec.url()).isEqualTo("https://hub.docker.com/v2/repositories/library/?page_size=100");
    assertThat(spec.notFoundMessage()).contains("Docker Hub");
}
```

`CredentialServiceTest` 里加：

```java
@Test
void dockerHubRejectsCredentialsBecauseItIsAnonymousOnly() {
    CredentialCreateRequest req = new CredentialCreateRequest();
    req.setName("hub");
    req.setType("DOCKERHUB");
    req.setEndpoint("docker.io");
    req.setAuthType("BASIC");
    req.setUsername("u");
    req.setSecret("p");

    assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(CloudPlatformException.class)
            .hasMessageContaining("该类型支持的认证方式");
}
```

- [ ] **Step 2: 跑测试确认它失败**

```bash
mvn -o -q -pl platform-api -am test -Dtest='DockerHubDialectTest,CredentialServiceTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL —— `suggest` 返回空、`TYPES` 里还没有 `DOCKERHUB` 所以类型校验先报别的错。

- [ ] **Step 3: `TYPES` 加值**

`CredentialService.java:55`：

```java
    private static final Set<String> TYPES = Set.of("GIT", "HARBOR", "DOCKERHUB");
```

- [ ] **Step 4: `suggest` / `browse` / `probeSpec` 的真实现**

替换 Task 5 留的三个占位：

```java
    @Override
    public List<String> suggest(CredentialRef cred, String imagePrefix) {
        // 补全**静默降级**：任何失败都退回空候选（spec §2.5 / S3.1 spec §5.3）
        try {
            String p = imagePrefix == null ? "" : imagePrefix.trim();
            String hostPrefix = hostPrefixOf(p);
            String rest = p.substring(hostPrefix.length());
            String tagPrefix = tagPrefixOf(p);

            if (tagPrefix != null) {
                String repoPart = tagPrefix.isEmpty()
                        ? rest
                        : rest.substring(0, rest.length() - tagPrefix.length() - 1);
                if (repoPart.isEmpty()) {
                    return List.of();
                }
                List<TagInfo> tags = parseTags(http.get(cred,
                        tagsUrl(namespaceOf(repoPart), repoOf(repoPart))));
                String lower = tagPrefix.toLowerCase(Locale.ROOT);
                return tags.stream()
                        .map(TagInfo::name)
                        .filter(t -> t.toLowerCase(Locale.ROOT).startsWith(lower))
                        .map(t -> hostPrefix + repoPart + ":" + t)
                        .toList();
            }

            // 只打了 host（`docker.io` / `docker.io/`）：把整个 registry 拉出来既慢又没意义
            if (rest.isEmpty()) {
                return List.of();
            }
            return parseSearchHits(http.get(cred, searchUrl(rest))).stream()
                    .map(n -> hostPrefix + stripLibrary(n, hostPrefix))
                    .distinct()
                    .toList();
        } catch (Exception e) {
            log.warn("Docker Hub 候选查询失败（静默降级为空）prefix={}", imagePrefix, e);
            return List.of();
        }
    }

    @Override
    public BrowseResult browse(CredentialRef cred, List<String> path) {
        // 浏览**不吞错**：用户主动点了一层，什么都不说等于页面坏了（S3.1 spec §5.3）
        if (path == null || path.isEmpty()) {
            return new BrowseResult(List.of(), List.of(new BrowseResult.Node(
                    LIBRARY, true, null,
                    List.of(new BrowseResult.Detail("说明", "Docker 官方镜像库")))));
        }
        if (path.size() == 1) {
            String ns = path.get(0);
            List<String> repos = parseRepositoryNames(http.get(cred, repositoriesUrl(ns)));
            return new BrowseResult(
                    List.of(new BrowseResult.Crumb(ns, List.of(ns))),
                    repos.stream().map(r -> new BrowseResult.Node(r, true, null, List.of())).toList());
        }
        if (path.size() == 2) {
            String ns = path.get(0);
            String repo = path.get(1);
            List<TagInfo> tags = parseTags(http.get(cred, tagsUrl(ns, repo)));
            // 叶子的 copyText 与补全同一条规则：library 下剥前缀（spec §3.3）
            String copyName = (LIBRARY.equals(ns) ? "" : ns + "/") + repo;
            return new BrowseResult(
                    List.of(new BrowseResult.Crumb(ns, List.of(ns)),
                            new BrowseResult.Crumb(repo, List.of(ns, repo))),
                    tags.stream().map(t -> new BrowseResult.Node(
                            t.name(), false, copyName + ":" + t.name(),
                            List.of(new BrowseResult.Detail("推送时间",
                                            t.pushedAt() == null ? "—" : t.pushedAt()),
                                    new BrowseResult.Detail("大小", BrowseFormat.formatSize(t.size())),
                                    new BrowseResult.Detail("digest",
                                            t.digest() == null ? "—" : t.digest())))).toList());
        }
        return new BrowseResult(List.of(), List.of());
    }

    @Override
    public ProbeSpec probeSpec(CredentialRef cred) {
        // 该档只允许匿名，所以探的是「平台能不能出站访问 Docker Hub 的公共 API」，
        // 而不是「你的凭据有没有效」—— 没有凭据。用 library 因为它恒定存在。
        return new ProbeSpec(repositoriesUrl(LIBRARY),
                "Docker Hub 公共 API 返回 404：地址可能被网络中间件改写了");
    }
```

（`tagPrefixOf` 用 `HarborDialect.tagPrefixOf` 的同款实现 —— 在本类里再写一份私有 static，**不要**跨档调用：
两档对 `:` 的处理将来可能分叉。）

- [ ] **Step 5: 把 `notFoundMessage` 接进出站层**

`CredentialProbeClient` 加一个重载：

```java
    public ProbeResult probeRegistry(String url, String username, String secret) {
        return probeRegistry(url, username, secret, null);
    }

    /**
     * @param notFoundMessage 该档对 404 的解释（{@link com.coding.platformapi.registry.ProbeSpec#notFoundMessage()}）。
     *                        null = 用通用文案。
     */
    public ProbeResult probeRegistry(String url, String username, String secret, String notFoundMessage) {
        return probe(url, username, secret, false, notFoundMessage);
    }
```

`probe(...)` 加一个 `String notFoundMessage` 参数，并在 classify 分支里用：

```java
                        if (git) {
                            return CredentialProbe.classifyGit(status, body, location);
                        }
                        return CredentialProbe.classifyRegistry(status, anonymous,
                                notFoundMessage == null
                                        ? "地址不对：该地址不是 registry（/v2/ 不存在）"
                                        : notFoundMessage);
```

`CredentialProbe.classifyRegistry` 相应地再加一个 `String notFoundMessage` 参数（Task 3 的两个参数版本保留成委托重载，别改既有测试）：

```java
    public static ProbeResult classifyRegistry(int status, boolean anonymous) {
        return classifyRegistry(status, anonymous, "地址不对：该地址不是 registry（/v2/ 不存在）");
    }

    public static ProbeResult classifyRegistry(int status, boolean anonymous, String notFoundMessage) {
        if (status == 200) {
            return ProbeResult.pass();
        }
        if (status == 401) {
            return anonymous
                    ? ProbeResult.pass("可访问（该 registry 要求认证，匿名仍可拉取公开仓库）")
                    : ProbeResult.fail("凭据不通过，或该凭据无权访问此 registry");
        }
        if (status == 403) {
            return ProbeResult.fail(anonymous
                    ? "该 registry 拒绝了这个来源的匿名访问"
                    : "凭据不通过，或该凭据无权访问此 registry");
        }
        if (status == 404) {
            return ProbeResult.fail(notFoundMessage);
        }
        return ProbeResult.fail("对端返回 " + status);
    }
```

`CredentialService.test` 里改成：

```java
            ProbeSpec spec = dialects.of(c.getType()).probeSpec(ref);
            result = probeClient.probeRegistry(spec.url(), s.getUsername(), s.getSecret(),
                    spec.notFoundMessage());
```

- [ ] **Step 6: 跑测试确认通过**

```bash
mvn -o -q -pl platform-api -am test -Dtest='DockerHubDialectTest,CredentialServiceTest,CredentialProbeTest,CredentialProbeClientTest,RegistryDialectRegistryTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。**护栏测试这时应该自动覆盖 `DOCKERHUB`** —— 若它红了，说明 `TYPES` 加了值但没有实现类。

- [ ] **Step 7: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/ platform-api/src/test/java/com/coding/platformapi/
git commit -m "feat(registry): DockerHubDialect 接线 + TYPES 加 DOCKERHUB（只允许匿名）"
```

---

### Task 7: `HarborDialect` 无 host → 全局搜索

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/registry/HarborDialect.java:44-90,230-234`
- Test: `platform-api/src/test/java/com/coding/platformapi/registry/HarborDialectTest.java`

**Interfaces:**
- Consumes: Task 4 的归一化 `segmentsOf`；既有 `searchUrl` / `parseSearchHits`
- Produces: `HarborDialect.globalSearchTermOf(String imagePrefix)` → `String`

- [ ] **Step 1: 写失败的测试**

```java
@Test
void noHostInputFallsBackToAGlobalKeywordSearch() {
    server.expect(requestTo("https://harbor.example.com/api/v2.0/search?q=ng"))
            .andRespond(withSuccess("""
                    {"repository":[{"repository_name":"proj/nginx"},
                                   {"repository_name":"base/nginx-web"}]}""",
                    MediaType.APPLICATION_JSON));

    assertThat(dialect.suggest(cred("harbor.example.com"), "ng"))
            .containsExactly("harbor.example.com/proj/nginx", "harbor.example.com/base/nginx-web");
}

@Test
void globalSearchTakesTheLastSegmentAsTheKeyword() {
    // `myteam/app` 里的 `myteam` 是命名空间不是 host（Docker 的规则），关键词取最后一段
    assertThat(HarborDialect.globalSearchTermOf("myteam/app")).isEqualTo("app");
    assertThat(HarborDialect.globalSearchTermOf("ng")).isEqualTo("ng");
    assertThat(HarborDialect.globalSearchTermOf("myteam/app:1")).isEqualTo("app");
}

@Test
void globalSearchCandidatesAlwaysCarryTheHost() {
    // Harbor 的镜像引用必须带 host，不带就没法拉 —— 所以候选串一律拼 host，与 Docker Hub 档相反
    server.expect(requestTo("https://harbor.example.com/api/v2.0/search?q=ng"))
            .andRespond(withSuccess("""{"repository":[{"repository_name":"proj/nginx"}]}""",
                    MediaType.APPLICATION_JSON));
    assertThat(dialect.suggest(cred("harbor.example.com"), "ng"))
            .containsExactly("harbor.example.com/proj/nginx");
}

@Test
void hostOnlyInputStillMakesNoSearchRequest() {
    assertThat(dialect.suggest(cred("harbor.example.com"), "harbor.example.com/")).isEmpty();
}
```

- [ ] **Step 2: 跑测试确认它失败**

```bash
mvn -o -q -pl platform-api -am test -Dtest=HarborDialectTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL —— 无 host 的输入现在返回空。

- [ ] **Step 3: 加分支**

`HarborDialect.suggest` 开头（在 `tagPrefixOf` 之前）：

```java
            String p = imagePrefix == null ? "" : imagePrefix.trim();

            // ⚠️ 输入**不以本连接的 host 开头** = 用户没打 host（隐式 Docker Hub 写法）。
            // 聚合之后这里只可能是"没打 host"：打了别家 host 时 RegistryService 根本不会轮到本连接
            // （带 host 只问匹配上的那一条）。见 spec §2.1。
            if (!p.toLowerCase(Locale.ROOT)
                    .startsWith(RegistryTargetResolver.normalizeEndpoint(cred.endpoint()))) {
                String term = globalSearchTermOf(p);
                if (term.isEmpty()) {
                    return List.of();
                }
                // 命中形如 proj/repo —— 候选串**必须带 host**（Harbor 引用不带 host 没法拉）
                return parseSearchHits(http.get(cred, searchUrl(cred, term))).stream()
                        .filter(h -> h.contains("/"))
                        .map(h -> host(cred) + "/" + h)
                        .toList();
            }
```

`HarborDialect` 新增：

```java
    /**
     * 全局搜索的关键词：去掉 tag、取最后一段。
     * {@code ng} → {@code ng}；{@code myteam/app} → {@code app}；{@code myteam/app:1} → {@code app}。
     *
     * <p>不取第一段：那一段在"有 host"时是 host、在"无 host"时是 Docker Hub 的命名空间，
     * 语义不稳定；最后一段永远是仓库名。
     */
    public static String globalSearchTermOf(String imagePrefix) {
        String p = imagePrefix == null ? "" : imagePrefix.trim();
        if (p.isEmpty()) {
            return "";
        }
        int colon = p.lastIndexOf(':');
        if (colon >= 0 && p.indexOf('/', colon) < 0) {
            p = p.substring(0, colon);
        }
        int slash = p.lastIndexOf('/');
        return slash < 0 ? p : p.substring(slash + 1);
    }
```

⚠️ 同时把 `suggest` 里已经用到的 `imagePrefix.trim()` 统一成上面的 `p`，别两处各 trim 一次。

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -o -q -pl platform-api -am test -Dtest=HarborDialectTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/registry/HarborDialect.java platform-api/src/test/java/com/coding/platformapi/registry/HarborDialectTest.java
git commit -m "feat(registry): Harbor 档支持无 host 输入 → 全局关键词搜索"
```

---

### Task 8: `RegistryTargetResolver` 认 Docker Hub 的三个别名

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/registry/RegistryTargetResolver.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/registry/RegistryTargetResolverTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `RegistryTargetResolver.ALIASES`（`Map<String,String>`，别名 → 规范名）

- [ ] **Step 1: 写失败的测试**

```java
@Test
void allThreeDockerHubAliasesMatchTheSameConnection() {
    // ⭐ Review Focus #3 的同族：配了 Docker Hub 却因为写法不同补不出来，用户没有任何线索
    CredentialRef hub = new CredentialRef("c1", "hub", "DOCKERHUB", "docker.io", null, null);
    List<CredentialRef> candidates = List.of(hub);

    assertThat(RegistryTargetResolver.match("docker.io/ng", candidates)).isEqualTo(hub);
    assertThat(RegistryTargetResolver.match("index.docker.io/ng", candidates)).isEqualTo(hub);
    assertThat(RegistryTargetResolver.match("registry-1.docker.io/ng", candidates)).isEqualTo(hub);
}

@Test
void canonicalizingAliasesDoesNotBreakPrefixBoundary() {
    // `docker.io.evil.com` 不能被当作 `docker.io`
    CredentialRef hub = new CredentialRef("c1", "hub", "DOCKERHUB", "docker.io", null, null);
    assertThat(RegistryTargetResolver.match("docker.io.evil.com/ng", List.of(hub))).isNull();
}
```

- [ ] **Step 2: 跑测试确认它失败**

```bash
mvn -o -q -pl platform-api -am test -Dtest=RegistryTargetResolverTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 前三条里至少两条 FAIL。

- [ ] **Step 3: 归一化别名**

`RegistryTargetResolver` 里加：

```java
    /**
     * 指向同一个 registry 的等价写法，统一成一个规范名再比较。
     *
     * <p>Docker Hub 有三个都在用的域名。不归一化的话，用户打
     * {@code registry-1.docker.io/ng} 而连接配的是 {@code docker.io} 就匹配不上 ——
     * 现象是「明明配了 Docker Hub 却补不出来」，而补全是静默降级，用户没有任何线索。
     */
    private static final Map<String, String> ALIASES = Map.of(
            "index.docker.io", "docker.io",
            "registry-1.docker.io", "docker.io");
```

在 `match` 里比较前套一层（host 与 endpoint 两侧都要）：

```java
    public static CredentialRef match(String imagePrefix, List<CredentialRef> candidates) {
        String host = canonical(hostOf(imagePrefix));
        if (host.isEmpty() || candidates == null || candidates.isEmpty()) {
            return null;
        }
        return candidates.stream()
                .filter(c -> boundaryMatches(host, canonical(normalizeEndpoint(c.endpoint()))))
                .max(Comparator.comparingInt(c -> canonical(normalizeEndpoint(c.endpoint())).length()))
                .orElse(null);
    }

    private static String canonical(String hostOrEndpoint) {
        return ALIASES.getOrDefault(hostOrEndpoint, hostOrEndpoint);
    }
```

⚠️ 顺序要紧：**先 `normalizeEndpoint`（去 scheme/尾斜杠/小写）再 `canonical`（换别名）** ——
反过来 `registry-1.docker.io` 会因为带点而被原样留下，别名表就白写了。

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -o -q -pl platform-api -am test -Dtest=RegistryTargetResolverTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS，且**既有的边界用例（`harbor.example.com.evil.com`）仍然绿**。

- [ ] **Step 5: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/registry/RegistryTargetResolver.java platform-api/src/test/java/com/coding/platformapi/registry/RegistryTargetResolverTest.java
git commit -m "feat(registry): 匹配层认 Docker Hub 的三个等效域名"
```

---

### Task 9: `RegistrySuggestResponse` 带 `connectionName`

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/models/RegistrySuggestResponse.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/RegistryService.java`（本任务只让它编译：把 `List<String>` 包成 `Item`，聚合留到 Task 10）

**Interfaces:**
- Produces: `RegistrySuggestResponse(List<Item> items)`；`RegistrySuggestResponse.Item(String value, String connectionName)`

- [ ] **Step 1: 写失败的测试**

`RegistryServiceTest` 里加：

```java
@Test
void eachCandidateCarriesTheNameOfTheConnectionItCameFrom() {
    when(mapper.selectAll()).thenReturn(List.of(
            row("c1", "harbor-prod", "HARBOR", "harbor.example.com")));
    when(dialect.suggest(any(), any())).thenReturn(List.of("harbor.example.com/proj/nginx"));

    RegistrySuggestResponse r = service.suggest("ng");

    assertThat(r.items()).singleElement()
            .satisfies(i -> {
                assertThat(i.value()).isEqualTo("harbor.example.com/proj/nginx");
                assertThat(i.connectionName()).isEqualTo("harbor-prod");
            });
}
```

- [ ] **Step 2: 跑测试确认它失败**

```bash
mvn -o -q -pl platform-api -am test -Dtest=RegistryServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败（`items()` 还是 `List<String>`）。

- [ ] **Step 3: 换 DTO**

`RegistrySuggestResponse.java`：

```java
package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 补全结果。
 *
 * <p>每条都带**来源连接名**：聚合之后一次输入会同时问多条连接，
 * 而「这条候选是哪个仓库来的」是唯一无法从候选串里推出来的信息
 * （Harbor 档的候选串自带 host，但"是哪个连接"推不出来）。
 */
@Schema(description = "镜像候选值")
public record RegistrySuggestResponse(List<Item> items) {

    /**
     * @param value          要填进输入框的**完整字符串**，前端整段替换、不做任何拼接
     * @param connectionName 来源连接名，**只用于展示**。注意它不是唯一键
     *                       （{@code platform_credential.name} 上有唯一约束，但前端不该依赖它做后续查询）
     */
    public record Item(String value, String connectionName) {
    }
}
```

`RegistryService.suggest` 里把 `new RegistrySuggestResponse(dialects.of(cred.type()).suggest(...))`
临时包成：`new RegistrySuggestResponse(list.stream().map(v -> new Item(v, row.getName())).toList())`。
（Task 10 会替换整段。）

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -o -q -pl platform-api -am test -Dtest='RegistryServiceTest,RegistryControllerTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。`RegistryControllerTest` 里若有断言响应体的用例，跟着改成对象数组。

- [ ] **Step 5: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/ platform-api/src/test/java/com/coding/platformapi/
git commit -m "feat(registry): 补全响应带来源连接名"
```

---

### Task 10: 跨连接聚合 + 并发

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/registry/RegistrySuggestExecutor.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/RegistryService.java:55-94`
- Test: `platform-api/src/test/java/com/coding/platformapi/registry/RegistrySuggestExecutorTest.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/RegistryServiceTest.java`

**Interfaces:**
- Consumes: Task 8 的别名归一化；Task 9 的 `RegistrySuggestResponse.Item`
- Produces: `RegistrySuggestExecutor.allOf(List<Callable<List<String>>>)` → `List<List<String>>`（**顺序与入参一一对应**）

- [ ] **Step 1: 写失败的测试**

`RegistryServiceTest`：

```java
@Test
void inputWithAHostOnlyAsksTheMatchingConnection() {
    when(mapper.selectAll()).thenReturn(List.of(
            row("c1", "harbor-prod", "HARBOR", "harbor.example.com"),
            row("c2", "hub", "DOCKERHUB", "docker.io")));

    service.suggest("harbor.example.com/proj/ap");

    assertThat(callsPerConnection).containsExactly("c1");   // c2 一次都没被问
}

@Test
void inputWithAHostThatMatchesNothingMakesZeroRequests() {
    when(mapper.selectAll()).thenReturn(List.of(
            row("c1", "harbor-prod", "HARBOR", "harbor.example.com")));

    assertThat(service.suggest("other.example.com/ng").items()).isEmpty();
    assertThat(calls).isEmpty();          // ⭐ 零请求，不是"发了但返回空"
}

@Test
void inputWithoutAHostAsksEveryConnection() {
    when(mapper.selectAll()).thenReturn(List.of(
            row("c1", "harbor-prod", "HARBOR", "harbor.example.com"),
            row("c2", "hub", "DOCKERHUB", "docker.io")));

    service.suggest("ng");

    assertThat(callsPerConnection).containsExactlyInAnyOrder("c1", "c2");
}

@Test
void inputWithoutAHostWithNoConnectionsMakesZeroRequests() {
    when(mapper.selectAll()).thenReturn(List.of());
    assertThat(service.suggest("ng").items()).isEmpty();
    assertThat(calls).isEmpty();
}

@Test
void resultsAreMergedAndSortedByConnectionName() {
    when(mapper.selectAll()).thenReturn(List.of(
            row("c2", "zeta", "HARBOR", "zeta.example.com"),
            row("c1", "alpha", "HARBOR", "alpha.example.com")));
    when(dialect.suggest(any(), any())).thenAnswer(inv -> {
        CredentialRef c = inv.getArgument(0);
        return List.of(c.endpoint() + "/x");
    });

    assertThat(service.suggest("ng").items())
            .extracting(RegistrySuggestResponse.Item::connectionName)
            .containsExactly("alpha", "zeta");     // 按连接名，不按 mapper 的返回顺序
}

@Test
void oneFailingConnectionDoesNotSuppressTheOthers() {
    // ⭐ Review Focus #1：一条连接的密文解不开（例如主密钥换过），其余候选必须照常返回。
    // S3.1 终审修过一次放大效应 —— 那时是「解不开就让所有人的补全全挂」，
    // 改成「只解密命中的那一条」之后，要保证那个"只影响它自己"真的成立。
    PlatformCredential broken = row("bad", "broken", "HARBOR", "broken.example.com");
    broken.setSecretEncrypted("v1:not-a-real-ciphertext");   // 用本测试这个 cipher 解不开
    PlatformCredential good = row("ok", "good", "HARBOR", "good.example.com");
    good.setSecretEncrypted(cipher.encrypt("{\"username\":\"u\",\"secret\":\"p\"}"));
    when(mapper.selectAll()).thenReturn(List.of(broken, good));
    when(dialect.suggest(any(), any())).thenReturn(List.of("good.example.com/x"));

    assertThat(service.suggest("ng").items())
            .extracting(RegistrySuggestResponse.Item::value)
            .containsExactly("good.example.com/x");
}
```

`RegistrySuggestExecutorTest`：

```java
@Test
void aHungTaskDoesNotDelayTheOthers() {
    // ⭐ Review Focus #1 的机制层：串行的话一条黑洞连接会把整条时间线拖到「最慢 × N」
    RegistrySuggestExecutor ex = new RegistrySuggestExecutor();
    long start = System.currentTimeMillis();
    List<List<String>> out = ex.allOf(List.of(
            () -> List.of("fast"),
            () -> { Thread.sleep(30_000); return List.of("never"); }));

    assertThat(out.get(0)).containsExactly("fast");
    assertThat(out.get(1)).isEmpty();                       // 被取消 → 空
    assertThat(System.currentTimeMillis() - start).isLessThan(10_000);
}

@Test
void aThrowingTaskBecomesAnEmptyResultNotAPropagatedException() {
    RegistrySuggestExecutor ex = new RegistrySuggestExecutor();
    List<List<String>> out = ex.allOf(List.of(
            () -> { throw new IllegalStateException("boom"); },
            () -> List.of("ok")));

    assertThat(out.get(0)).isEmpty();
    assertThat(out.get(1)).containsExactly("ok");
}
```

- [ ] **Step 2: 跑测试确认它失败**

```bash
mvn -o -q -pl platform-api -am test -Dtest='RegistrySuggestExecutorTest,RegistryServiceTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败（执行器不存在）。

- [ ] **Step 3: 建执行器**

`platform-api/src/main/java/com/coding/platformapi/registry/RegistrySuggestExecutor.java`：

```java
package com.coding.platformapi.registry;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 补全的并发出口。一次输入可能要让 N 条连接各自查一次 ——
 * 串行会把整条时间线拖到「最慢那条 × N」，而补全是每按键一次的高频动作。
 *
 * <p>设计见 docs/superpowers/specs/2026-10-14-registry-dockerhub-design.md §2.5。
 */
@Slf4j
@Component
public class RegistrySuggestExecutor {

    /**
     * 总等待上限。
     *
     * <p>⚠️ <b>刻意小于单条连接的最坏耗时</b>（{@code RegistryHttpClient} 是连接 5s + 读 10s，
     * 最坏 15s）。一条黑洞连接不该让用户的补全等 15 秒 —— 它被取消、按空算，
     * 而其余连接的结果照常返回。S3.1 时只有一条连接参与，不存在这个问题。
     */
    static final Duration BUDGET = Duration.ofSeconds(3);

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 并发跑完所有任务。**顺序与入参一一对应**（调用方按索引配对连接名）。
     *
     * <p>任何一条抛异常或超时都**只影响它自己** —— 该位置返回空列表，别的照常。
     * 这仍是 S3.1 spec §5.3 那条「补全静默降级」的姿态，只是**逐连接**适用。
     */
    public List<List<String>> allOf(List<Callable<List<String>>> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        List<Future<List<String>>> futures;
        try {
            futures = executor.invokeAll(tasks, BUDGET.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return tasks.stream().map(t -> List.<String>of()).toList();
        }
        List<List<String>> out = new ArrayList<>(futures.size());
        for (Future<List<String>> f : futures) {
            try {
                List<String> v = f.get();
                out.add(v == null ? List.of() : v);
            } catch (Exception e) {
                // CancellationException（超时被取消）与 ExecutionException（对方抛）都在这里
                log.warn("镜像候选查询失败或超时（静默降级为空）", e);
                out.add(List.of());
            }
        }
        return out;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
```

- [ ] **Step 4: `RegistryService.suggest` 改成聚合**

把第 62-94 行整段替换成：

```java
    /**
     * 补全。
     *
     * <h4>匹配模型（S3.1 的契约在这里被反转）</h4>
     * <ul>
     *   <li><b>带 host</b> → 只问匹配上的那一条（匹配不上则零请求）；</li>
     *   <li><b>不带 host</b>（{@code nginx} / {@code myteam/app}）→ <b>所有启用的镜像仓库连接都问</b>，
     *       结果按连接名排序后合并。</li>
     * </ul>
     * S3.1 时无 host 一律返回空，因为当时只有 Harbor 一档、无法确定该归哪条连接 ——
     * 结论没错，但代价是 Docker Hub 档只能在用户手打 {@code docker.io/} 时生效，等于白做。
     * 聚合把「猜哪条」换成「都给」，那个只能猜的歧义就没有了。
     */
    public RegistrySuggestResponse suggest(String imagePrefix) {
        if (!StringUtils.hasText(imagePrefix)) {
            return new RegistrySuggestResponse(List.of());
        }
        String prefix = imagePrefix.trim();
        List<PlatformCredential> rows = enabledRegistryRows();
        if (rows.isEmpty()) {
            return new RegistrySuggestResponse(List.of());
        }

        List<PlatformCredential> targets = pickTargets(prefix, rows);
        if (targets.isEmpty()) {
            return new RegistrySuggestResponse(List.of());
        }
        // 排序在**发请求之前**做好 —— 结果按同一顺序拼回来，不需要二次匹配
        List<PlatformCredential> ordered = targets.stream()
                .sorted(Comparator.comparing(PlatformCredential::getName,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        List<Callable<List<String>>> tasks = ordered.stream()
                .map(c -> (Callable<List<String>>) () -> cachedSuggest(c, prefix))
                .toList();
        List<List<String>> results = suggestExecutor.allOf(tasks);

        List<RegistrySuggestResponse.Item> items = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            String name = ordered.get(i).getName();
            for (String value : results.get(i)) {
                items.add(new RegistrySuggestResponse.Item(value, name));
            }
        }
        return new RegistrySuggestResponse(items);
    }

    /**
     * 挑本次参与补全的连接。
     *
     * <p>⚠️ <b>带 host 且匹配不上时返回空列表 = 零请求</b>，这是 spec §2.6 的硬边界：
     * 用户输入里的 host 只用于匹配，绝不参与构造目标地址。
     */
    private List<PlatformCredential> pickTargets(String prefix, List<PlatformCredential> rows) {
        if (RegistryTargetResolver.hostOf(prefix).isEmpty()) {
            return rows;                                  // 无 host：全给（聚合的意义所在）
        }
        CredentialRef hit = RegistryTargetResolver.match(prefix,
                rows.stream().map(RegistryService::toPlainRef).toList());
        if (hit == null) {
            return List.of();
        }
        return rows.stream().filter(r -> r.getId().equals(hit.id())).toList();
    }

    /**
     * 单条连接：解密 → 方言 → 缓存。
     *
     * <p>⚠️ <b>只解密命中的那一条</b> —— S3.1 终审修过一次放大效应：
     * 以前在每次 suggest（每按键一次）里解密**全部**凭据，任何一条密文解不开
     * （例如主密钥换过）就让**所有人**的补全全挂。现在解不开只会让那一条没候选。
     *
     * <p>⚠️ key 传的是**逻辑键**：{@code RedisJsonCache} 自己会加 {@code platform:cache:} 前缀。
     */
    private List<String> cachedSuggest(PlatformCredential row, String prefix) {
        CredentialRef cred = toRef(row);
        return cache.get("registry:suggest:" + cred.id() + ":" + prefix,
                Duration.ofMinutes(2),
                new TypeReference<>() { },
                () -> dialects.of(cred.type()).suggest(cred, prefix));
    }
```

（注入 `RegistrySuggestExecutor suggestExecutor`；加 import：`java.util.Comparator`、`java.util.ArrayList`、`java.util.concurrent.Callable`。）

- [ ] **Step 5: 跑测试确认通过**

```bash
mvn -o -q -pl platform-api -am test -Dtest='RegistryServiceTest,RegistrySuggestExecutorTest,RegistryControllerTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。**既有那条「匹配不到时不调方言」的用例现在是对带 host 的输入成立的** —— 若它是用无 host 输入写的，改成带 host（契约变了，测试要跟着变，别把断言删掉）。

- [ ] **Step 6: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/ platform-api/src/test/java/com/coding/platformapi/
git commit -m "feat(registry): 候选跨连接聚合（无 host 全给、带 host 只问一条；并发 3s 预算）"
```

---

### Task 11: 前端类型与 API 客户端

**Files:**
- Modify: `platform-web/src/types.ts:1640-1670` 附近
- Modify: `platform-web/src/api/index.ts:638-653`

**Interfaces:**
- Consumes: Task 9 的响应结构
- Produces: `RegistrySuggestItem`；`registryApi.suggest` 返回 `RegistrySuggestResponse`

- [ ] **Step 1: 改类型**

`types.ts`：

```ts
/** 一条镜像候选。value = 要填进输入框的完整串；connectionName 只用于展示。 */
export interface RegistrySuggestItem {
  value: string
  connectionName: string
}

export interface RegistrySuggestResponse {
  items: RegistrySuggestItem[]
}
```

同一文件里凭据的两处联合类型也要扩：

```ts
  type: 'GIT' | 'HARBOR' | 'DOCKERHUB'
  authType: 'BASIC' | 'NONE'
```

（`grep -n "'GIT' | 'HARBOR'" platform-web/src/types.ts` 定位 —— S3.1 时有三处。）

- [ ] **Step 2: 改 API 客户端**

`api/index.ts`：

```ts
  suggest: (imagePrefix: string) =>
    http.post<never, RegistrySuggestResponse>('/registry/suggest', { imagePrefix }, { silent403: true }),
```

（`silent403` 那段 javadoc **保留不动** —— 它讲的理由没变。）

- [ ] **Step 3: 类型检查**

```bash
cd platform-web && npx vue-tsc --noEmit -p tsconfig.json
```
Expected: 会**报错**，指出 `useImageSuggest.ts` 与两处 `.vue` 用了旧形状 —— 这正是下一步要改的。记下报错清单。

- [ ] **Step 4: 提交**

```bash
git add platform-web/src/types.ts platform-web/src/api/index.ts
git commit -m "feat(web): 补全响应改成带来源连接名的对象数组"
```

---

### Task 12: 前端下拉显示来源连接名

**Files:**
- Modify: `platform-web/src/composables/useImageSuggest.ts`
- Modify: `platform-web/src/views/resource/WorkloadView.vue:34,646-650`
- Modify: `platform-web/src/components/workload/ContainerEditor.vue:19,119-125`

**Interfaces:**
- Consumes: Task 11 的 `RegistrySuggestItem`
- Produces: `fetchImageSuggestions(query, cb)`，`cb` 收到 `RegistrySuggestItem[]`

- [ ] **Step 1: 改 composable**

`useImageSuggest.ts`：

```ts
import { registryApi } from '@/api'
import { apiCodes } from '@/apiCodes'
import type { RegistrySuggestItem } from '@/types'
import { usePermission } from '@/stores/permission'

/**
 * 镜像输入框的候选补全（S3.1 / Docker Hub 增补）。两处用到：`WorkloadView` 的「更新镜像」弹窗、
 * `ContainerEditor` 的容器镜像框。
 *
 * <p>四条规矩，缺一不可：
 * <ol>
 *   <li><b>无权限时不发请求</b> —— 与 frontend-permission-conventions §4 的「可选拉取短路」同一条；</li>
 *   <li><b>失败静默</b> —— 返回空候选，不弹错。补全是每按键一次的高频动作，弹错会掩盖真正的问题，
 *       也会让用户以为「填不了镜像」（spec §5.3）；</li>
 *   <li><b>选中即整段替换</b> —— 后端给的候选已经是完整镜像串，前端不做任何拼接
 *       （拼接规则是方言相关的，见 `RegistryDialect` 的 javadoc）；</li>
 *   <li><b>候选带来源连接名</b> —— 一次输入会同时问多条连接，同一行右侧要标出它来自哪条
 *       （后端按连接名排好序，前端不再排）。</li>
 * </ol>
 */
export function useImageSuggest() {
  const perm = usePermission()

  function fetchImageSuggestions(
    query: string,
    cb: (items: RegistrySuggestItem[]) => void,
  ): void {
    const q = (query ?? '').trim()
    if (!q || !perm.has(apiCodes.registryCatalog)) {
      cb([])
      return
    }
    registryApi
      .suggest(q)
      .then((r) => cb(r.items ?? []))
      .catch(() => cb([])) // 静默降级：候选为空不是错误态
  }

  return { fetchImageSuggestions }
}
```

- [ ] **Step 2: 改两处模板**

两处 `el-autocomplete` 都要加 `#default` 插槽。`ContainerEditor.vue`（第 119 行附近，**保留原有的 placeholder 文案与宽度**）：

```vue
          <el-autocomplete v-model="image" :fetch-suggestions="fetchImageSuggestions"
                           placeholder="镜像（如 nginx:1.27）" style="width: 320px">
            <!-- ⚠️ key 不能只用 value：两条不同连接返回同一个串是可能的（同 endpoint 的两条连接），
                 只用 value 会出重复 key、渲染丢行 -->
            <template #default="{ item }">
              <div class="suggest-row">
                <span class="suggest-value">{{ item.value }}</span>
                <span class="suggest-source">{{ item.connectionName }}</span>
              </div>
            </template>
          </el-autocomplete>
```

`WorkloadView.vue`（第 646 行附近）同样处理，保留它原有的
`placeholder="如 nginx:1.27 或 registry.example.com/app"` 与 `style="width: 100%"`。

同一个 SFC 的 `<style scoped>` 里加（两处各加一份；`flex` 让右侧靠边、`min-width:0` 让长镜像串能省略号截断）：

```css
.suggest-row {
  display: flex;
  align-items: center;
  gap: 16px;
  min-width: 0;
}

.suggest-value {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.suggest-source {
  flex: none;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
```

⚠️ 若这两处原本就**没有** `<style scoped>` 块，直接加一个；**不要**用 `:deep()` 去改
`el-autocomplete` 内部节点 —— 那些节点没有 `data-v-*`，scoped 选择器匹配不到
（本仓踩过，见 `element-plus-dialog-scoped-css` 那条）。

- [ ] **Step 3: 类型检查 + 构建**

```bash
cd platform-web && npx vue-tsc --noEmit -p tsconfig.json && npm run build
```
Expected: 两条都干净。**⚠️ 必须跑 `npm run build`** —— S3.1 时踩过 `vue-tsc` 通过而 build 报
`error TS2532`（`noUncheckedIndexedAccess`），**两个检查不等价，构建期更严**。

- [ ] **Step 4: 提交**

```bash
git add platform-web/src/
git commit -m "feat(web): 镜像补全下拉右侧标出来源连接名"
```

---

### Task 13: `CredentialView.vue` 表单

**Files:**
- Modify: `platform-web/src/views/CredentialView.vue:31-38,250-270` 附近

**Interfaces:**
- Consumes: Task 11 的 `authType: 'BASIC' | 'NONE'` 与 `type` 加 `DOCKERHUB`
- Produces: 无（叶子）

- [ ] **Step 1: 加选项与常量**

`CredentialView.vue` 顶部：

```ts
const TYPE_OPTIONS = [
  { value: 'GIT', label: 'Git 仓库' },
  { value: 'HARBOR', label: 'Harbor 镜像仓库' },
  { value: 'DOCKERHUB', label: 'Docker Hub 镜像仓库' },
]

const AUTH_OPTIONS = [
  { value: 'BASIC', label: '用户名 + 密码/令牌' },
  { value: 'NONE', label: '匿名（不使用凭据）' },
]

/** Docker Hub 档只允许匿名，endpoint 也是固定值 —— 两个都由后端定（spec §3.1 / §3.6）。 */
const isDockerHub = computed(() => form.type === 'DOCKERHUB')

/** 该档是否要显示认证方式与凭据框。Docker Hub 只有一个选项，给控件是噪音。 */
const showsAuthFields = computed(() => !isDockerHub.value)
```

- [ ] **Step 2: 加「认证方式」下拉 + 联动**

在「地址」表单项之后插入：

```vue
        <el-form-item v-if="showsAuthFields" label="认证方式">
          <el-select v-model="form.authType" style="width: 100%">
            <el-option v-for="o in AUTH_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
```

把用户名/密码两个表单项各加 `v-if="showsAuthFields && form.authType === 'BASIC'"`。

地址那一项加动态 `:disabled`：

```vue
        <el-form-item label="地址" required>
          <el-input v-model="form.endpoint" :disabled="isDockerHub"
                    :placeholder="isDockerHub ? 'docker.io' : 'harbor.example.com'" />
        </el-form-item>
```

- [ ] **Step 3: 联动逻辑**

换类型时（`form.type` 的 watcher，或 `el-select` 的 `@change`）：

```ts
function onTypeChange(t: string): void {
  if (t === 'DOCKERHUB') {
    // 该档只允许匿名、endpoint 固定（后端也会校验，这里只是别让用户白填）
    form.authType = 'NONE'
    form.endpoint = 'docker.io'
    form.username = ''
    form.secret = ''
  } else if (form.authType === 'NONE' && t === 'DOCKERHUB') {
    form.authType = 'BASIC'
  }
}
```

⚠️ 打开**编辑**弹窗时不能跑这个联动 —— 否则编辑一条已存好的行会把它的值冲掉。只在
`el-select` 的 `@change` 上挂，不在 `watch(form.type)` 上挂。

提交时按 `form.authType` 决定带不带 `username` / `secret`（`NONE` 时两个字段都不发，
后端会拒掉带了凭据的匿名请求）。

- [ ] **Step 4: 类型检查 + 构建 + 浏览器实测**

```bash
cd platform-web && npx vue-tsc --noEmit -p tsconfig.json && npm run build
```
Expected: 干净。

再起 preview 实测（后端在跑时）：
1. 凭据页 → 新建 → 类型选「Docker Hub 镜像仓库」
2. 断言：地址框变只读且值是 `docker.io`；「认证方式」与用户名/密码**整组消失**
3. 类型切回「Harbor 镜像仓库」→ 认证方式重新出现、地址框可编辑
4. 编辑一条**已存**的 Harbor 连接 → 打开时认证方式与用户名框的状态必须是对的（联动没被触发）

- [ ] **Step 5: 提交**

```bash
git add platform-web/src/views/CredentialView.vue
git commit -m "feat(web): 凭据表单支持匿名认证与 Docker Hub 档"
```

---

### Task 14: 订正三份文档（spec §12 的清单）

**Files:**
- Modify: `docs/superpowers/specs/2026-10-14-registry-design.md`（S3.1）
- Modify: `docs/superpowers/specs/2026-10-13-credential-management-design.md`（S0.2）
- Modify: `docs/task-ledger-2026-10-12.md`

**Interfaces:** 无（纯文档）

> 不订正的话三份 spec 会互相矛盾：S3.1 的 §4.1 写着「无 host 一律空候选」、§10 把 DOCKERHUB
> 列在「有意不做」、§11 说 probe 将来才挪；S0.2 的 `authType` 写着「v1 只有 BASIC」。
> 清单见本批 spec 的 §12 —— **逐条照它改**。

- [ ] **Step 1: 改 S3.1 spec**

按 `2026-10-14-registry-dockerhub-design.md` §12 表格里那 10 行逐条改。重点三处：

1. 顶部「**范围**：Harbor 一档」→ 「Harbor + Docker Hub 两档」，并指向本批 spec
2. **§4.1 整节重写**为本批 spec §2.1 的聚合模型（这是被反转的契约）
3. **§10 删掉两行**：「`REGISTRY_V2` / `DOCKERHUB` 两档」与「无 host 的镜像写法」

- [ ] **Step 2: 改 S0.2 spec**

三处（本批 spec §12 里那三行）：

1. 认证方式处：取值域 `BASIC` → `BASIC | NONE`；补「哪种认证由各类型的
   `RegistryDialect.authTypes()` 声明，GIT 是常量」与「匿名时 `/v2/` 的 401 判为通过」
2. 测试连接处：补匿名分支（不发 `Authorization` 头；404 文案由 `ProbeSpec` 给）
3. 「永不回显」处：补一句「匿名行的 `hasSecret = false`」

**顺带**：`V2026_10_13_3__credential.sql` 的列注释里已经写了「可空是为将来 NONE 型（匿名）留位」
—— 在 S0.2 spec 里指出这一点（说明这不是临时改动，是当初就留好的位）。

- [ ] **Step 3: 改台账**

`docs/task-ledger-2026-10-12.md`：

1. **S3.1 条目**：把「② S3.1.1 只落 Harbor 一档，`REGISTRY_V2` / `DOCKERHUB` 不做（用户：…）」
   这行改掉 —— 它是**记账错误**。改成：「Docker Hub 于 2026-10-14 补做（见
   `specs/2026-10-14-registry-dockerhub-design.md`）；`REGISTRY_V2` 记为欠账，
   它同样没有搜索接口，补全做不出来。」
2. **S0.2 条目**：补「2026-10-14 扩 `authType` 支持匿名（`NONE`）；顺带修掉『填了凭据就再也清不掉』的死路」
3. **进度表**：S3.1 行的状态保持「已实施待部署」，Spec 列加上本批那份

- [ ] **Step 4: 提交**

```bash
git add docs/
git commit -m "docs: 订正三份 spec 与台账（聚合契约 / authType NONE / Docker Hub 记账错误）"
```

---

### Task 15: 全量回归

**Files:** 无（只跑）

- [ ] **Step 1: 后端全量**

```bash
mvn -o -q install -Dmaven.test.skip=true && mvn -o test
```
⚠️ **先 install**：`mvn -pl platform-api spring-boot:run` 用的是**已安装的 platform-data jar**，
不是 reactor 源码（S0.2 踩过 `NoClassDefFoundError`）。

Expected: **platform-common 19 · k8s-core 210 · k8s-server 20 · platform-api ≥407**，全绿。
`k8s-core` 的 210 依赖工作区里那个**有意删掉的** `WorkloadConverterStaticIpTest.java`。

- [ ] **Step 2: 前端**

```bash
cd platform-web && npx vue-tsc --noEmit -p tsconfig.json && npm run build
```
Expected: 两条都干净，**0 条 TS error**。⚠️ `vue-tsc` 通过不代表 build 通过（S3.1 踩过）。

- [ ] **Step 3: 启动校验**

用**没被占用的端口**起一次（用户本机 8081 常年被自己的实例占着）：

```bash
PLATFORM_CREDENTIAL_AES_KEY=test-only-master-key-0123456789abcdef \
  mvn -o -q -pl platform-api spring-boot:run -Dspring-boot.run.arguments=--server.port=18085
```

看三件事：

1. `[RBAC] 交叉校验通过：**276** 个 endpoint` —— **必须还是 276**（本批没新增端点）。
   若变成别的数，说明有端点漏了权限行或多加了路径。
2. 启动没有 `该镜像仓库类型尚未实现`
3. 日志里**没有** `RedisJsonCache` 双前缀之类的告警

- [ ] **Step 4: 冒烟（按 spec §13.1 的清单走）**

逐条走 `docs/superpowers/specs/2026-10-14-registry-dockerhub-design.md` §13.1，重点四条：

1. 建一条 Docker Hub 连接 —— 除名称外一个字段都不用填
2. 打 `ng` → Harbor 与 Docker Hub 的候选**同时**出现，右侧是连接名、**按连接名排序**
3. 打 `test.harbor.com/proj/ap` → 行为与 S3.1 交付时**完全一致**（回归）
4. 审计页确认补全与浏览**没有**产生记录

⚠️ **本批的 §11 待实测项（Docker Hub 匿名的实际可达性、`repo_name` 形状、Harbor 匿名 `/v2/` 的 401）
只能在真实网络里验** —— 结论回来补进那份 spec 的 §11 表格。

- [ ] **Step 5: 确认工作区只剩用户自己的改动**

```bash
git status --short
```
Expected: 只有这三条（**都不是本批的，不要提交**）：

```
 D k8s-core/src/test/java/com/coding/k8score/converter/impl/workload/WorkloadConverterStaticIpTest.java
 M platform-api/src/main/resources/application.yaml
 M platform-common/src/main/java/com/coding/common/utils/AESUtils.java
```

---

## 自审记录

**Spec 覆盖**：逐节对过 `2026-10-14-registry-dockerhub-design.md` —— §2（聚合）→ Task 8/9/10；
§3.1（枚举与 endpoint）→ Task 6/13；§3.2（四个接口）→ Task 5/6；§3.3（两层浏览）→ Task 6；
§3.4（候选串）→ Task 5；§3.5（tag 解析）→ Task 5/6；§3.6（只允许匿名）→ Task 6/13；
§3.7（`authType` NONE）→ Task 1/2/3；§4（probe 挪接口）→ Task 2/6；§5（Harbor 全局搜索）→ Task 7；
§6（前端）→ Task 11/12/13；§7（权限与迁移）→ 无任务（**spec 说零改动**）；
§8（审计）→ 无任务（**spec 说零改动**）；§9（测试）→ 分散在各任务；§12（文档订正）→ Task 14；
§13（部署）→ Task 15。**没有缺口。**

**计划外新增的一项**：Task 4（`HarborDialect` 段切分归一化）。
它是探索期发现的**现存 bug**，spec 里没有 —— 但它与 §3.2 的"末端要归一化"是同一件事，
且不修的话 Task 7 的分支判断会误判。已在任务里写明它是 bug 修复而非重构。

**类型一致性**：`RegistrySuggestResponse.Item`（Task 9）→ Task 10 使用、Task 11 的
`RegistrySuggestItem` 对应；`ProbeSpec`（Task 2）→ Task 6 使用；`BrowseFormat.TagInfo`
（Task 5）→ Task 6 使用；`RegistrySuggestExecutor.allOf`（Task 10）只被 Task 10 使用。
逐一对过签名，一致。
