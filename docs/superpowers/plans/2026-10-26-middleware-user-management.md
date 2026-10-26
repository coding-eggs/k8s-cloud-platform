# 中间件用户管理（改口令 / 增删用户）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 八款中间件（MySQL / PostgreSQL / Redis / RabbitMQ / Elasticsearch / Milvus / Kafka / Nacos）的实例详情页都能管理账号：改内置口令、增/删用户、给 2~3 个预设角色；明文口令只在创建/改口令的响应里出现一次。

**Architecture:** 与既有中间件同构，三层各管一段 —— `platform-api.MiddlewareService` 管归属校验、口令生成与"一次性返回"；`k8s-server.MiddlewareUserService` 按 code 分派到每家的 `MiddlewareUserAdapter`（与 `MiddlewareAdapter` 同形，**不抽公共基类**），执行通道按家选（PG 走 CRD、ES/RabbitMQ/Milvus 走 HTTP、MySQL 走 Pod exec、Redis 走 Secret+重启）；前端只渲染后端下发的角色与账号列表。

**Tech Stack:** Java 21 / Spring Boot 4.x、fabric8 Kubernetes Client（含 `pod.exec`）、`java.net.http.HttpClient`、Vue 3 + Element Plus + TypeScript、MySQL 迁移 SQL。

**Spec:** `docs/superpowers/specs/2026-10-26-middleware-user-management-design.md`

## Global Constraints

- 只有支持清单里的 code 能进用户管理：`mysql` / `postgresql` / `redis` / `rabbitmq` / `elasticsearch` / `milvus` / `kafka` / `nacos`；其余明确报「该中间件暂不支持用户管理」。
- 用户名规则统一 **`^[a-z0-9_]{3,32}$`**（Kafka / Nacos 也是这套，不接受大写与连字符）。
- 口令长度 **8~72**（72 = Milvus `defaultRootPassword` 上限，全平台按它卡）；留空 → 平台生成（18 字节随机 → Base64 URL-safe）。
- 口令去向：普通用户写 Secret **`<实例名>-users`**（key = 用户名）；内置账号**另外**写回各家自己的凭据 Secret。
- **明文只出现在 `create` / `password` 的响应里，仅一次**；不进日志、不进审计、不进操作记录、不进前端 store。
- 审计 action：`middleware:user:create` / `:password` / `:delete`，`target = #request.instanceId`；**不新增权限码**。
- 执行身份统一 **admin client**（租户 SA 的 RBAC 模板里没有 `pods/exec`）；授权仍在 platform-api 层（`requireInstance` + 分配表）。
- 内置账号允许改口令、**不允许删除**；平台建的账号可改可删。
- 迁移文件编号：本批唯一的 SQL 是 **`V2026_10_26_2__kafka_user_rbac.sql`**（`V2026_10_26_1` 已被应用编排占用），跑完必须「重下发全部模板」。
- 测试命令统一带 `-Dsurefire.failIfNoSpecifiedTests=false`（`-am` 会带上没有匹配测试的模块）。

## Review Focus

- **口令泄露到不该去的地方**：异常消息、日志、审计摘要、操作记录快照里都不能出现明文 —— 期望 `MiddlewareUserService` 的所有 `CloudPlatformException` 文案只带用户名与目标地址（Task 9 的 `errorsNeverContainThePassword`）。
- **改库成功但 Secret 没更新**：两边不一致比"没改成功"更糟 —— 期望顺序固定「先改事实来源，再写 Secret」，且写 Secret 失败时错误文案明确说"已改、Secret 未同步"（Task 3 的 `changePasswordWritesSecretOnlyAfterBackendSucceeds`）。
- **内置账号被误删/误建**：平台不允许删内置账号，也不允许建与内置同名的账号 —— 期望 `drop` 与 `create` 都先过白名单（Task 3 的 `refusesDroppingBuiltinUser`、`refusesCreatingReservedUsername`）。
- **Kafka 的 listener 变更打断现有客户端**：加 `scram` listener 必须**保留** `plain`，且不动 `authorization` —— 期望渲染出的 Kafka CR 里两个 listener 都在、`authorization` 不存在（Task 10 的 `keepsPlainListenerAndAddsScram`）。
- **Nacos 未开鉴权时的用户页签**：没开 `auth.enabled` 的实例不该出现用户入口 —— 期望平台侧按 `specJson.authEnabled` 过滤、接口直接拒绝（Task 11 的 `nacosWithoutAuthRejectsUserManagement`）。

---

## Task 1: 公共契约与支持清单

**Files:**
- Create: `platform-common/src/main/java/com/coding/common/models/middleware/MiddlewareUserView.java`
- Create: `platform-common/src/main/java/com/coding/common/models/middleware/MiddlewareUserListRequestDTO.java`
- Create: `platform-common/src/main/java/com/coding/common/models/middleware/MiddlewareUserCredentialDTO.java`
- Create: `platform-common/src/main/java/com/coding/common/models/middleware/MiddlewareUserCreateRequestDTO.java`
- Create: `platform-common/src/main/java/com/coding/common/models/middleware/MiddlewareUserPasswordRequestDTO.java`
- Create: `platform-common/src/main/java/com/coding/common/models/middleware/MiddlewareUserDeleteRequestDTO.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/audit/AuditAction.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/MiddlewareUserSupportTest.java`

**Interfaces:**
- Produces: 上面五个 DTO（字段见下），`AuditAction.MIDDLEWARE_USER_CREATE/PASSWORD/DELETE`。
  ```java
  @Data public class MiddlewareUserView { String username; String role; boolean builtin; String secretName; String secretKey; }
  @Data public class MiddlewareUserCredentialDTO { String username; String password; String secretName; String secretKey; }
  @Data public class MiddlewareUserListRequestDTO { String instanceId, clusterId, namespace, name, tenantId; }
  @Data public class MiddlewareUserCreateRequestDTO { String instanceId, clusterId, namespace, name, username, password, role, tenantId; }
  @Data public class MiddlewareUserPasswordRequestDTO { String instanceId, clusterId, namespace, name, username, newPassword, tenantId; }
  @Data public class MiddlewareUserDeleteRequestDTO { String instanceId, clusterId, namespace, name, username, tenantId; }
  ```

- [ ] **Step 1: 写失败测试（支持清单 + 用户名规则）**

`platform-api/src/test/java/com/coding/platformapi/services/MiddlewareUserSupportTest.java`：

```java
package com.coding.platformapi.services;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MiddlewareUserSupportTest {

    @Test
    void supportedCodesCoverTheEightMiddlewares() {
        assertThat(MiddlewareUserService.SUPPORTED_CODES).containsExactlyInAnyOrder(
                "mysql", "postgresql", "redis", "rabbitmq", "elasticsearch", "milvus", "kafka", "nacos");
    }

    @Test
    void usernamesAreLowercaseWithUnderscoresOnly() {
        assertThat(MiddlewareUserService.validUsername("alice_1")).isTrue();
        assertThat(MiddlewareUserService.validUsername("Alice")).isFalse();
        assertThat(MiddlewareUserService.validUsername("al")).isFalse();      // 太短
        assertThat(MiddlewareUserService.validUsername("a-b")).isFalse();     // 连字符
        assertThat(MiddlewareUserService.validUsername("x".repeat(33))).isFalse();
    }

    @Test
    void passwordsAreEightToSeventyTwo() {
        assertThatThrownBy(() -> MiddlewareUserService.requirePasswordLength("short"))
                .hasMessageContaining("8~72");
        assertThatThrownBy(() -> MiddlewareUserService.requirePasswordLength("x".repeat(73)))
                .hasMessageContaining("8~72");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl platform-api -am test -Dtest=MiddlewareUserSupportTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（`MiddlewareUserService` 不存在）

- [ ] **Step 3: 建 DTO 与常量**

按上面的字段建五个 DTO（`@Data`，与 `MiddlewarePasswordRequestDTO` 同风格）。`AuditAction` 追加：

```java
/** 中间件实例的用户管理（新增 / 改口令 / 删除） */
public static final String MIDDLEWARE_USER_CREATE = "middleware:user:create";
public static final String MIDDLEWARE_USER_PASSWORD = "middleware:user:password";
public static final String MIDDLEWARE_USER_DELETE = "middleware:user:delete";
```

先建一个只有常量的壳（服务本体在 Task 9 写），让本任务的测试能跑：

`platform-api/src/main/java/com/coding/platformapi/services/MiddlewareUserService.java`

```java
package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;

import java.util.Set;
import java.util.regex.Pattern;

/** 用户管理的平台侧规则（支持清单 / 用户名 / 口令长度）。 */
public final class MiddlewareUserService {

    public static final Set<String> SUPPORTED_CODES = Set.of(
            "mysql", "postgresql", "redis", "rabbitmq", "elasticsearch", "milvus", "kafka", "nacos");

    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9_]{3,32}$");

    private MiddlewareUserService() {
    }

    public static boolean validUsername(String username) {
        return username != null && USERNAME.matcher(username).matches();
    }

    public static void requirePasswordLength(String password) {
        if (password == null || password.length() < 8 || password.length() > 72) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "口令长度需在 8~72 位之间");
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o -pl platform-api -am test -Dtest=MiddlewareUserSupportTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 3, Failures: 0`

- [ ] **Step 5: 提交**

```bash
git add platform-common/src/main/java/com/coding/common/models/middleware/ \
        platform-api/src/main/java/com/coding/platformapi/audit/AuditAction.java \
        platform-api/src/main/java/com/coding/platformapi/services/MiddlewareUserService.java \
        platform-api/src/test/java/com/coding/platformapi/services/MiddlewareUserSupportTest.java
git commit -m "feat(middleware): 用户管理契约（DTO / 支持清单 / 审计动作）"
```

---

## Task 2: k8s-server 用户服务骨架（接口 / Secret 工具 / 分派 / 控制器）

**Files:**
- Create: `k8s-server/src/main/java/com/coding/k8sserver/middleware/MiddlewareUserAdapter.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/middleware/MiddlewareUserRef.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/middleware/UsersSecret.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/middleware/MiddlewareUserService.java`
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/controllers/middleware/MiddlewareInstanceController.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/middleware/UsersSecretTest.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/middleware/MiddlewareUserServiceTest.java`

**Interfaces:**
- Produces:
  ```java
  public interface MiddlewareUserAdapter {
      String code();
      List<MiddlewareUserView> list(KubernetesClient client, MiddlewareUserRef ref);
      void create(KubernetesClient client, MiddlewareUserRef ref, String username, String password, String role);
      void drop(KubernetesClient client, MiddlewareUserRef ref, String username);
      void changePassword(KubernetesClient client, MiddlewareUserRef ref, String username, String newPassword);
      List<String> roles(MiddlewareUserRef ref);   // 多数是常量；Milvus 要现查实例
  }
  public record MiddlewareUserRef(String instanceName, String namespace, String instanceId) {}
  ```
- Consumes: Task 1 的五个 DTO。

- [ ] **Step 1: 写 `UsersSecret` 的失败测试**

`k8s-server/src/test/java/com/coding/k8sserver/middleware/UsersSecretTest.java`：

```java
package com.coding.k8sserver.middleware;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UsersSecretTest {

    @Test
    void secretNameIsDerivedFromInstanceName() {
        assertThat(UsersSecret.name("mw-mysql-abcdefgh")).isEqualTo("mw-mysql-abcdefgh-users");
    }

    @Test
    void readsPasswordsIgnoringMissingAndBlankKeys() {
        Secret s = new SecretBuilder()
                .addToData("alice", b64("pw-alice"))
                .addToData("bob", "")
                .build();
        assertThat(UsersSecret.passwords(s))
                .containsEntry("alice", "pw-alice")
                .doesNotContainKey("bob");
        assertThat(UsersSecret.passwords(null)).isEmpty();
    }

    private static String b64(String v) {
        return Base64.getEncoder().encodeToString(v.getBytes(StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl k8s-server -am test -Dtest=UsersSecretTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（`UsersSecret` 不存在）

- [ ] **Step 3: 实现 `UsersSecret`**

```java
package com.coding.k8sserver.middleware;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 平台建的用户存在哪：Secret {@code <实例名>-users}，key = 用户名、value = 口令。
 *
 * <p>只用于"平台建的账号"；内置账号的口令在各自 operator 的 Secret 里（§4）。
 */
final class UsersSecret {

    private UsersSecret() {
    }

    static String name(String instanceName) {
        return instanceName + "-users";
    }

    /** 读全部非空口令（用户名 → 明文）；Secret 不存在返回空 map。 */
    static Map<String, String> passwords(Secret secret) {
        Map<String, String> out = new LinkedHashMap<>();
        if (secret == null || secret.getData() == null) {
            return out;
        }
        secret.getData().forEach((k, v) -> {
            if (v != null && !v.isBlank()) {
                out.put(k, new String(Base64.getDecoder().decode(v), StandardCharsets.UTF_8));
            }
        });
        return out;
    }

    static Secret read(KubernetesClient client, String namespace, String instanceName) {
        return client.secrets().inNamespace(namespace).withName(name(instanceName)).get();
    }
}
```

- [ ] **Step 4: 建 `MiddlewareUserAdapter` / `MiddlewareUserRef` / `MiddlewareUserService`**

`MiddlewareUserService` 的职责（本任务只做出骨架 + 分派 + Secret 读写；各家适配器在后续任务逐个补）：

```java
@Service
public class MiddlewareUserService {
    private final Map<String, MiddlewareUserAdapter> adapters;   // code → adapter
    private final ResourceAccessResolver accessResolver;
    private final KubernetesClientFactory clients;

    public List<MiddlewareUserView> list(MiddlewareUserListRequestDTO req);
    public MiddlewareUserCredentialDTO create(MiddlewareUserCreateRequestDTO req);
    public MiddlewareUserCredentialDTO changePassword(MiddlewareUserPasswordRequestDTO req);
    public void drop(MiddlewareUserDeleteRequestDTO req);
}
```

要点（写进实现，不要走样）：
- 客户端一律 `clients.getAdminClient(req.getClusterId())`，**不**用 `accessResolver` 的租户 client（租户 SA 没有 `pods/exec`）；
- 口令写 `<实例名>-users` 在**适配器执行成功之后**；写失败抛 `CloudPlatformException("口令已生效，但 Secret <名称>-users 未更新：…")`；
- `create`/`password` 返回值里带明文；`list`/`drop` 不带。

- [ ] **Step 5: 控制器加四个端点**

`MiddlewareInstanceController` 追加（与既有端点同风格，`AccessBoundary.TENANT`）：

```java
@PostMapping("/{code}/instances/users")          // 列表
@PostMapping("/{code}/instances/users/create")   // 新增
@PostMapping("/{code}/instances/users/password") // 改口令
@PostMapping("/{code}/instances/users/delete")   // 删除
```

- [ ] **Step 6: 分派的失败测试**

`MiddlewareUserServiceTest`（只测纯逻辑，不碰集群）：

```java
@Test
void unknownCodeIsRejected() {
    assertThatThrownBy(() -> svc.adapterFor("oracle"))
            .hasMessageContaining("暂不支持用户管理");
}

@Test
void redisOnlyAllowsPasswordChange() {
    assertThatThrownBy(() -> svc.requireCreatable("redis"))
            .hasMessageContaining("不支持新增用户");
}
```

- [ ] **Step 7: 跑测试确认通过**

Run: `mvn -o -pl k8s-server -am test -Dtest='UsersSecretTest,MiddlewareUserServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 全绿

- [ ] **Step 8: 提交**

```bash
git add k8s-server/src/main/java/com/coding/k8sserver/middleware/ \
        k8s-server/src/main/java/com/coding/k8sserver/controllers/middleware/MiddlewareInstanceController.java \
        k8s-server/src/test/java/com/coding/k8sserver/middleware/UsersSecretTest.java \
        k8s-server/src/test/java/com/coding/k8sserver/middleware/MiddlewareUserServiceTest.java
git commit -m "feat(middleware): k8s-server 用户服务骨架（分派 / users Secret / 四个端点）"
```

---

## Task 3: MySQL 适配器（Pod exec）

**Files:**
- Create: `k8s-server/src/main/java/com/coding/k8sserver/middleware/exec/PodExec.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/middleware/user/MysqlUserAdapter.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/middleware/user/MysqlUserAdapterTest.java`

**Interfaces:**
- Produces:
  ```java
  public final class PodExec {
      /** 在 Pod 里跑一条命令，返回 stdout；非 0 退出码抛异常（stderr 进消息）。 */
      public static String run(KubernetesClient client, String namespace, String pod, String container,
                               Map<String, String> env, String... command);
  }
  ```
  角色常量：`READ_ONLY` / `READ_WRITE`（值 `只读` / `读写`，与 UI 一致）。

- [ ] **Step 1: 写失败测试（SQL 拼装 + 白名单）**

```java
class MysqlUserAdapterTest {

    @Test
    void createStatementQuotesUsernameAndGrantsTheRole() {
        assertThat(MysqlUserAdapter.createSql("alice", "Rw-pass-123", "读写"))
                .contains("CREATE USER 'alice'@'%' IDENTIFIED BY 'Rw-pass-123'")
                .contains("GRANT ALL PRIVILEGES ON *.* TO 'alice'@'%'");
        assertThat(MysqlUserAdapter.createSql("bob", "pw-123456", "只读"))
                .contains("GRANT SELECT ON *.* TO 'bob'@'%'");
    }

    @Test
    void dropStatementTargetsHostPercent() {
        assertThat(MysqlUserAdapter.dropSql("alice")).isEqualTo("DROP USER 'alice'@'%'");
    }

    @Test
    void refusesDroppingBuiltinUser() {
        assertThatThrownBy(() -> MysqlUserAdapter.requireNotBuiltin("root"))
                .hasMessageContaining("内置账号");
    }

    @Test
    void refusesCreatingReservedUsername() {
        assertThatThrownBy(() -> MysqlUserAdapter.requireNotBuiltin("mysql.sys"))
                .hasMessageContaining("系统账号");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl k8s-server -am test -Dtest=MysqlUserAdapterTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败

- [ ] **Step 3: 实现 `PodExec`**

用 fabric8 `pod.exec(...)`（`PodExecWebSocketHandler` 已验证可用），要点：
- 命令用 `sh -c "..."`；`env` 用 `withEnv`（`MYSQL_PWD` 走这里，**不进 argv**）；
- 输出上限 8KB、超时 15s（`ExecWatch` + `CountDownLatch`）；
- 非 0 返回码 → `CloudPlatformException`，消息里只放 stderr 前 300 字。

- [ ] **Step 4: 实现 `MysqlUserAdapter`**

- `roles()` → `List.of("只读", "读写")`；
- `list`：`SELECT user FROM mysql.user WHERE user NOT IN ('root','mysql.sys','mysql.session','mysql.infoschema') AND host='%'`，
  内置 `root` 按 `builtin=true` 放最前；只认 `<实例名>-users` 里有 key 的普通账号（§3.3）；
- `create`：`requireNotBuiltin` → `createSql(...)` 一条 `-e` 执行；
- `drop`：`requireNotBuiltin` → `dropSql`；
- `changePassword`：`root` 走 `ALTER USER 'root'@'%' IDENTIFIED BY '<new>'` → 成功后写回 `<名称>-secrets`；
  普通用户走 `ALTER USER '<u>'@'%' IDENTIFIED BY '<new>'`；
- 连接参数：Pod `<实例名>-mysql-0`，容器 `mysql`；`MYSQL_PWD` 取 `<名称>-secrets` 的 `root` 键。
- SQL 字符串转义：口令里的单引号/反斜杠要转义（`\\'`），单测补一条 `quotesUsernamesSoInjectionCannotEscape`。

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -o -pl k8s-server -am test -Dtest=MysqlUserAdapterTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 全绿

- [ ] **Step 6: 提交**

`git commit -m "feat(middleware): MySQL 用户管理适配器（Pod exec）"`

---

## Task 4: PostgreSQL 适配器（DatabaseRole 声明式）

**Files:**
- Create: `k8s-server/src/main/java/com/coding/k8sserver/middleware/user/PostgresqlUserAdapter.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/middleware/user/PostgresqlUserAdapterTest.java`

**Interfaces:**
- Produces: `DatabaseRole` / 每用户 Secret 的构造纯函数：
  ```java
  static GenericKubernetesResource databaseRole(String name, String cluster, String secretName, String role);
  static Secret userSecret(String name, String username, String password);
  ```

- [ ] **Step 1: 写失败测试**

```java
@Test
void databaseRoleBindsLoginToTheSecretAndBuiltinRoles() {
    var cr = PostgresqlUserAdapter.databaseRole("alice", "mw-pg-abcdefgh", "alice-pw", "只读");
    Map<String, Object> spec = (Map<String, Object>) cr.getAdditionalProperties().get("spec");
    assertThat(spec).containsEntry("login", true).containsEntry("ensure", "present");
    assertThat((List<String>) spec.get("inRoles")).containsExactly("pg_read_all_data");
    assertThat((Map<String, Object>) spec.get("passwordSecret"))
            .containsEntry("name", "alice-pw");
    assertThat(cr.getKind()).isEqualTo("DatabaseRole");
}

@Test
void readWriteAddsTheWriteRole() {
    var cr = PostgresqlUserAdapter.databaseRole("bob", "c", "s", "读写");
    assertThat((List<String>) spec(cr).get("inRoles"))
            .containsExactly("pg_read_all_data", "pg_write_all_data");
}

@Test
void userSecretIsBasicAuth() {
    assertThat(PostgresqlUserAdapter.userSecret("alice-pw", "alice", "pw-123456").getType())
            .isEqualTo("kubernetes.io/basic-auth");
}
```

- [ ] **Step 2: 跑测试确认失败**（同前，`-Dtest=PostgresqlUserAdapterTest`）

- [ ] **Step 3: 实现**

- `roles()` → `只读` / `读写`；
- `list`：列 `databaseroles.postgresql.cnpg.io` 且 `spec.cluster == <实例名>` 的 CR（平台建的）+ 内置应用账号（`<实例名>-app` 的 `username` 键）；
- `create`：建 Secret `<实例名>-user-<username>`（type `kubernetes.io/basic-auth`）+ `DatabaseRole`（`metadata.name = username`）；
- `drop`：改 `DatabaseRole.spec.ensure = absent`，删每用户 Secret；
- `changePassword`：普通用户改每用户 Secret 的 `password`；内置账号改 `<实例名>-app`；
- ⚠️ 实施第一步真集群验证「改 `-app` Secret 会不会被 operator 回写」（spec §6.2），验不过就把改内置口令降级。

- [ ] **Step 4: 跑测试确认通过，Step 5 提交**

`git commit -m "feat(middleware): PostgreSQL 用户管理（DatabaseRole 声明式）"`

---

## Task 5: Redis 适配器（只改口令）

**Files:**
- Create: `k8s-server/src/main/java/com/coding/k8sserver/middleware/user/RedisUserAdapter.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/middleware/user/RedisUserAdapterTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void onlyTheDefaultUserIsListed() {
    var users = RedisUserAdapter.builtinUsers();
    assertThat(users).singleElement()
            .satisfies(u -> { assertThat(u.getUsername()).isEqualTo("default"); assertThat(u.isBuiltin()).isTrue(); });
}

@Test
void creatingOrDroppingUsersIsRejected() {
    assertThatThrownBy(() -> RedisUserAdapter.refuseUserCrud("create")).hasMessageContaining("只支持修改口令");
    assertThatThrownBy(() -> RedisUserAdapter.refuseUserCrud("drop")).hasMessageContaining("只支持修改口令");
}
```

- [ ] **Step 2–4: 实现**

- `changePassword`：改 `<实例名>-credentials` 的 `password` → `client.apps().statefulSets().inNamespace(ns).withName(name).rolling().restart()`（与平台既有"重启"写法一致）；
- `list` 只有 `default`；`create`/`drop` 直接抛「Redis 只支持修改口令」；
- ⚠️ 真集群验「改 Secret 会不会被 opstree 回写」；回写则整条能力降级（`roles()` 返回空、UI 不显示）。

- [ ] **Step 5: 跑测试 + 提交**

`git commit -m "feat(middleware): Redis 用户管理（仅改口令）"`

---

## Task 6: RabbitMQ 适配器（Management API）

**Files:**
- Create: `k8s-server/src/main/java/com/coding/k8sserver/middleware/user/RabbitmqUserAdapter.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/middleware/user/RabbitmqUserAdapterTest.java`

- [ ] **Step 1: 写失败测试（payload 纯函数）**

```java
@Test
void permissionsMapRolesToTheThreeFlags() {
    assertThat(RabbitmqUserAdapter.permissions("只读"))
            .containsEntry("configure", "").containsEntry("write", "").containsEntry("read", ".*");
    assertThat(RabbitmqUserAdapter.permissions("读写"))
            .containsEntry("configure", ".*").containsEntry("write", ".*").containsEntry("read", ".*");
}

@Test
void adminRoleAddsTheAdministratorTag() {
    assertThat(RabbitmqUserAdapter.userBody("pw-123456", "管理员"))
            .containsEntry("password", "pw-123456")
            .containsEntry("tags", "administrator");
    assertThat(RabbitmqUserAdapter.userBody("pw-123456", "只读")).containsEntry("tags", "");
}

@Test
void internalUsersAreFilteredOut() {
    assertThat(RabbitmqUserAdapter.isInternal("rabbitmq_management")).isTrue();
    assertThat(RabbitmqUserAdapter.isInternal("alice")).isFalse();
}
```

- [ ] **Step 3: 实现**

- 凭据：`<实例名>-default-user` 的 `username` / `password`（basic auth）；
- base URL：`http://<实例名>.<ns>.svc:15672`；
- `list`：`GET /api/users` 过滤内部账号；
- `create`：`PUT /api/users/<u>`（body 见上）+ `PUT /api/permissions/%2F/<u>`；
- `drop`：`DELETE /api/users/<u>`；
- `changePassword`：再 `PUT /api/users/<u>`（内置账号同样适用）；
- HTTP 用 `java.net.http.HttpClient`，超时 10s，非 2xx 抛错（消息带状态码 + 前 300 字）。

- [ ] **Step 5: 测试 + 提交**

`git commit -m "feat(middleware): RabbitMQ 用户管理（Management API）"`

---

## Task 7: Elasticsearch 适配器（HTTPS + CA）

**Files:**
- Create: `k8s-server/src/main/java/com/coding/k8sserver/middleware/user/ElasticsearchUserAdapter.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/middleware/user/EsHttpClient.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/middleware/user/ElasticsearchUserAdapterTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void roleMapsToBuiltinEsRoles() {
    assertThat(ElasticsearchUserAdapter.esRoles("只读")).containsExactly("viewer");
    assertThat(ElasticsearchUserAdapter.esRoles("读写")).containsExactly("editor");
    assertThat(ElasticsearchUserAdapter.esRoles("管理员")).containsExactly("superuser");
}

@Test
void userBodyCarriesPasswordAndRoles() {
    assertThat(ElasticsearchUserAdapter.userBody("pw-123456", "只读"))
            .contains("\"password\":\"pw-123456\"").contains("\"viewer\"");
}

@Test
void onlyElasticAndPlatformPrefixAreShown() {
    assertThat(ElasticsearchUserAdapter.isShown("elastic")).isTrue();
    assertThat(ElasticsearchUserAdapter.isShown("platform-alice")).isTrue();
    assertThat(ElasticsearchUserAdapter.isShown("kibana_system")).isFalse();
}
```

- [ ] **Step 3: 实现**

- 凭据：`<实例名>-es-elastic-user` 的 `elastic` 键；
- Scheme：读 ES CR `spec.http.tls` 是否存在 → https（CA 从 `<实例名>-es-http-certs-public` 的 `ca.crt` 建 `SSLContext`；只信这张 CA，**不 trust-all**）；
- base URL：`https://<实例名>-es-http.<ns>.svc:9200`（`tls=false` 时 `http://`）；
- 端点：列表 `GET /_security/user`、新增 `POST /_security/user/<u>`、删除 `DELETE /_security/user/<u>`、改口令 `POST /_security/user/<u>/_password`；
- 用户命名：平台建的账号加前缀 `platform-`（spec §6.5）；UI 展示时去掉前缀；
- 内置 `elastic` 改口令成功后**同时**写回 `<实例名>-es-elastic-user`。

- [ ] **Step 5: 测试 + 提交**

`git commit -m "feat(middleware): Elasticsearch 用户管理（HTTPS + ECK CA）"`

---

## Task 8: Milvus 适配器（补增删 + 角色现查）

**Files:**
- Create: `k8s-server/src/main/java/com/coding/k8sserver/middleware/user/MilvusUserAdapter.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/middleware/user/MilvusUserAdapterTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void createGoesThroughUsersCreateThenGrantRole() {
    List<String> paths = MilvusUserAdapter.operationPaths("create");  // 顺序即调用顺序
    assertThat(paths).containsExactly("/v2/vectordb/users/create", "/v2/vectordb/roles/grant_role");
}

@Test
void onlyAdminAndPublicAreEligibleRoles() {
    assertThat(MilvusUserAdapter.assignableRoles(List.of("admin", "public")))
            .containsExactly("admin");     // public 无权限，不给选
}

@Test
void rootIsTheOnlyBuiltin() {
    assertThat(MilvusUserAdapter.BUILTIN).isEqualTo("root");
}
```

- [ ] **Step 3: 实现**

- 地址：`http://<实例名>-milvus.<ns>.svc:19530`（复用 `MilvusPasswordClient` 的 HttpClient 风格）；
- `list`：`users/list` → 只保留 `root` + `<实例名>-users` 里有 key 的；内置 `root` 标 `builtin`；
- `create`：`users/create`（`userName` + `password`）→ `roles/grant_role`（`roleName` + `userName`）；
- `drop`：`users/drop`；
- `changePassword`：**复用现有** `/middleware/instance/password` 的逻辑（`MilvusPasswordClient.update`）——本任务把它挪到适配器里，控制器端点保留兼容；
- `roles(ref)`：现查 `roles/list`，过滤掉 `public`。

- [ ] **Step 5: 测试 + 提交**

`git commit -m "feat(middleware): Milvus 用户管理（增删 + 角色现查）"`

---

## Task 9: platform-api 服务与控制器（一次性口令）

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/MiddlewareUserService.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/controllers/MiddlewareController.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/k8s/K8sMiddlewareClient.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/MiddlewareUserServiceTest.java`

**Interfaces:**
- Consumes: Task 1 的 DTO、Task 2 的四个 k8s-server 端点。
- Produces: `POST /middleware/instance/users{,/create,/password,/delete}`。

- [ ] **Step 1: 写失败测试**

```java
@Test
void createGeneratesAPasswordWhenBlankAndReturnsItOnce() {
    when(instances.selectByPrimaryKey("mwi_1")).thenReturn(milvus("t1"));
    when(client.createUser(anyString(), any())).thenReturn(dto("alice", "generated-pw-1"));

    var out = service.create(req("alice", null, "admin"), "t1");

    assertThat(out.getUsername()).isEqualTo("alice");
    assertThat(out.getPassword()).hasSizeGreaterThanOrEqualTo(8);
    assertThat(out.getSecretName()).isEqualTo("mw-milvus-abcdefgh-users");
    verify(client).createUser(anyString(), argThat(d -> d.getPassword().length() >= 8));
}

@Test
void createRejectsUnknownCodesAndBuiltinNames() {
    when(instances.selectByPrimaryKey("mwi_1")).thenReturn(instance("oracle", "t1"));
    assertThatThrownBy(() -> service.create(req("alice", null, "admin"), "t1"))
            .hasMessageContaining("暂不支持用户管理");

    when(instances.selectByPrimaryKey("mwi_1")).thenReturn(milvus("t1"));
    assertThatThrownBy(() -> service.create(req("root", null, "admin"), "t1"))
            .hasMessageContaining("内置账号");
}

@Test
void errorsNeverContainThePassword() {
    when(instances.selectByPrimaryKey("mwi_1")).thenReturn(milvus("t1"));
    when(client.createUser(anyString(), any())).thenThrow(new RuntimeException("boom"));

    assertThatThrownBy(() -> service.create(req("alice", "pw-SECRET-123", "admin"), "t1"))
            .hasMessageNotContaining("pw-SECRET-123");
}

@Test
void nacosWithoutAuthRejectsUserManagement() {
    var i = instance("nacos", "t1");
    i.setSpecJson("{\"authEnabled\":false}");
    when(instances.selectByPrimaryKey("mwi_1")).thenReturn(i);

    var q = new MiddlewareUserListRequestDTO();
    q.setInstanceId("mwi_1");
    assertThatThrownBy(() -> service.list(q, "t1"))
            .hasMessageContaining("没有开启鉴权");
    assertThatThrownBy(() -> service.create(req("alice", null, "admin"), "t1"))
            .hasMessageContaining("没有开启鉴权");
}
```

- [ ] **Step 3: 实现**

`MiddlewareUserService`（platform-api）：
- 四个方法都先 `requireInstance(instanceId, effectiveTenantId(顶栏 tenantId))`；
- code 不在 `SUPPORTED_CODES` → 「该中间件暂不支持用户管理」；
- `milvus` / `nacos` 还要看 `specJson.authEnabled`（没开鉴权 → 「实例没有开启鉴权，请重建并勾选」）；
- `redis` 只允许 `password`，`create`/`delete` → 「Redis 只支持修改口令」；
- 口令：留空 → `randomPassword()`；给定 → `trim()` + `requirePasswordLength`；
- 返回 `MiddlewareUserCredentialDTO`（含明文，仅此一次）。

`K8sMiddlewareClient` 追加四个方法（`exchangeSlow`，路径 `/middleware/{code}/instances/users…`）。

`MiddlewareController` 四个端点 + `@Auditable`（三个写动作）。

- [ ] **Step 4: 跑测试**

Run: `mvn -o -pl platform-api -am test -Dtest=MiddlewareUserServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 全绿

- [ ] **Step 5: 提交**

`git commit -m "feat(middleware): 用户管理端点（归属校验 / 一次性口令）"`

---

## Task 10: Kafka（第二个 SCRAM listener + KafkaUser）

**Files:**
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/middleware/KafkaMiddlewareAdapter.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/middleware/user/KafkaUserAdapter.java`
- Create: `platform-data/src/main/resources/db/migration/V2026_10_26_2__kafka_user_rbac.sql`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/middleware/user/KafkaUserAdapterTest.java`
- Modify: `k8s-server/src/test/java/com/coding/k8sserver/middleware/KafkaMiddlewareAdapterTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void keepsPlainListenerAndAddsScram() {
    var kafka = KafkaMiddlewareAdapter.kafkaSpecForTest();   // 抽出 listeners 拼装
    List<Map<String, Object>> listeners = (List) kafka.get("listeners");
    assertThat(listeners).extracting(l -> l.get("name")).containsExactly("plain", "scram");
    assertThat(listeners.get(1)).containsEntry("port", 9093);
    assertThat((Map<String, Object>) listeners.get(1).get("authentication"))
            .containsEntry("type", "scram-sha-512");
    assertThat(kafka).doesNotContainKey("authorization");    // 本批不开授权
}

@Test
void userCrUsesScramAuthentication() {
    var cr = KafkaUserAdapter.userCr("alice", "mw-kafka-abcdefgh");
    assertThat(cr.getKind()).isEqualTo("KafkaUser");
    assertThat((Map<String, Object>) cr.getAdditionalProperties().get("spec"))
            .containsEntry("authentication", Map.of("type", "scram-sha-512"));
}
```

- [ ] **Step 3: 实现**

- 适配器：`listeners` 从一条变两条（`plain` 保持原样；`scram` 9093 + `authentication.type: scram-sha-512`）；
- `KafkaUserAdapter`：用户 = `KafkaUser` CR（`metadata.name = username`，namespace 同实例）；口令在 operator 生成的同名 Secret 的 `password` 键；
- `changePassword` = 删 CR 再建（注释写明"Strimzi 只在创建时生成 SCRAM 凭据"）；
- `roles()` → 只有一个 `client`，UI 文案「实例未启用授权，所有用户全权」；
- 迁移：租户 RBAC 模板追加 `kafkausers`（仿 `V2026_10_25_8`，带 `NOT LIKE '%kafkausers%'` 守卫）。

- [ ] **Step 4: 测试 + 提交**

`git commit -m "feat(middleware): Kafka 用户管理（scram listener + KafkaUser）"`

---

## Task 11: Nacos（开启鉴权 + 管理员口令 + 用户/角色）

**Files:**
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/middleware/NacosChartInstaller.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/middleware/user/NacosUserAdapter.java`
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/middleware/MiddlewareInstanceService.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/middleware/NacosChartInstallerTest.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/middleware/user/NacosUserAdapterTest.java`

- [ ] **Step 1: 写失败测试（values）**

```java
@Test
void authEnabledRendersTheSwitch() {
    var req = nacosReq();
    req.setAuthEnabled(true);
    assertThat(installer.valuesFor(req)).contains("  auth:\n    enabled: true\n");
}

@Test
void authAbsentKeepsTheChartDefault() {
    assertThat(installer.valuesFor(nacosReq())).doesNotContain("enabled: true");
}
```

- [ ] **Step 3: 实现**

- `valuesFor`：`authEnabled=true` 时写 `nacos.auth.enabled: true`；
- `MiddlewareInstanceService.create`：Nacos + authEnabled → 生成管理员口令写 `<实例名>-nacos-admin`（`username`/`password`），并在 DTO 返回里按 Task 9 的一次性口径弹给用户（**创建实例时**也要弹一次，与用户口令同一套 UI）；
- `NacosUserAdapter`：先 `POST /nacos/v3/auth/user/login` 拿 accessToken，再 `POST/PUT/DELETE /nacos/v3/auth/user`；
  角色简化：`只读`/`读写`/`管理员` → Nacos 的 role + permission（映射写成纯函数 + 单测）；
- ⚠️ 实施第一步真集群验管理员口令初始化路径，验不通就把 Nacos 从支持清单去掉（`SUPPORTED_CODES` 减一项 + UI 不显示）。

- [ ] **Step 4: 测试 + 提交**

`git commit -m "feat(middleware): Nacos 用户管理（开启鉴权 + v3 auth API）"`

---

## Task 12: 前端「用户」页签

**Files:**
- Modify: `platform-web/src/views/MiddlewareDetailView.vue`
- Modify: `platform-web/src/api/index.ts`
- Modify: `platform-web/src/types.ts`
- Test: 手工（`npm run type-check` + `npm run build`）

- [ ] **Step 1: 加 API 与类型**

```ts
// api/index.ts
users: (instanceId: string, tenantId?: string) =>
  http.post<never, MiddlewareUser[]>('/middleware/instance/users', { instanceId, tenantId }),
createUser: (p: { instanceId: string; username: string; password?: string; role: string; tenantId?: string }) =>
  http.post<never, MiddlewareUserCredential>('/middleware/instance/users/create', p),
changeUserPassword: (p: { instanceId: string; username: string; newPassword?: string; tenantId?: string }) =>
  http.post<never, MiddlewareUserCredential>('/middleware/instance/users/password', p),
deleteUser: (p: { instanceId: string; username: string; tenantId?: string }) =>
  http.post<never, boolean>('/middleware/instance/users/delete', p),
```

- [ ] **Step 2: 详情页加 tab**

- `el-tabs` 里加 `用户` 页签（`lazy`）；显示条件：`code` 在支持清单，且（Kafka/Nacos）`spec.authEnabled === true`；
- 表格：用户名 / 角色 / 类型 / 操作；内置账号不出「删除」按钮；
- 「新增用户」弹窗：用户名、角色（后端下发）、口令（留空=平台生成）；
- 「改口令」弹窗：新口令（可留空）；
- **成功后接一次性明文弹窗**：

```vue
<el-dialog v-model="credential.visible" title="口令只显示这一次" width="480px">
  <p class="mono">{{ credential.username }} / {{ credential.password }}</p>
  <el-button @click="copy(credential.password)">复制</el-button>
  <p class="mw-form-help">
    已写入 Secret {{ credential.secretName }} 的 {{ credential.secretKey }} 键；关闭后不再显示。
  </p>
</el-dialog>
```
  口令只放 `credential` 这个 `reactive` 局部变量，**不进 store / 不进 URL**；关闭时把 `password` 置空。

- [ ] **Step 3: 验证**

Run: `npm --prefix platform-web run type-check && npm --prefix platform-web run build`
Expected: 均 0 退出

- [ ] **Step 4: 提交**

`git commit -m "feat(web): 中间件实例详情页新增用户管理页签"`

---

## Task 13: 文档与真集群验收清单

**Files:**
- Modify: `k8s-server/src/main/resources/charts/middleware/{mysql,postgresql,redis,rabbitmq,elasticsearch,kafka,nacos,milvus}/README.md`
- Create: `docs/superpowers/plans/2026-10-26-middleware-user-management-checklist.md`

- [ ] **Step 1: 每家 README 补一节「用户管理」**：机制、通道、角色映射、前置（Kafka listener / Nacos auth）、快照注意（存量实例要重放）。
- [ ] **Step 2: 写验收清单**：逐家「建实例 → 建用户 → 客户端连 → 改口令 → 新口令连 → 删用户 → 连不上」的具体命令与期望输出。
- [ ] **Step 3: 全量测试**

Run: `mvn -o test`
Expected: BUILD SUCCESS（platform-api / k8s-server 全绿）

- [ ] **Step 4: 提交**

`git commit -m "docs: 中间件用户管理（八家）文档与真集群验收清单"`

---

## 部署顺序（本批上线时按这个走）

1. 重放 SQL：`V2026_10_26_2__kafka_user_rbac.sql`；
2. 重建 **k8s-server**（适配器 + exec）与 **platform-api**（服务/控制器/审计）、**platform-common**（DTO）；
3. 重建 **platform-web**（用户页签 + 一次性明文弹窗）；
4. 管理员在「租户 RBAC 模板」页点一次 **重下发全部模板**（Kafka 的 `kafkausers` 权限才会进已有租户的 ClusterRole）；
5. 存量 **Kafka / Nacos** 实例各重放一次（Kafka 才有 9093；Nacos 才有 `auth.enabled`）；其余六家不用动；
6. 按 `2026-10-26-middleware-user-management-checklist.md` 逐家真集群验收，验不过的那家从
   `SUPPORTED_CODES` 去掉（UI 自动不显示用户页签）。
