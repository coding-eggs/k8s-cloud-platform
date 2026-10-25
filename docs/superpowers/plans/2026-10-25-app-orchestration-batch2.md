# 应用编排（画布）· 计划②：中间件节点 + 依赖注入 + 初始化 + 引用守卫

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 画布上能加 MySQL/Redis/Kafka/RabbitMQ/PostgreSQL/ES 节点（平台创建 / 绑定已有实例 / 链接外部），组件通过连线自动拿到连接信息，部署时先起中间件、再跑初始化、最后起组件；被引用的实例删不掉。

**Architecture:** 中间件节点的三种来源都归一成一份**结构化连接信息**（host/port/secret 键），由依赖解析器翻译成组件的环境变量（普通值 + `secretKeyRef`）；执行顺序在阶段机里插入 `MIDDLEWARE` 与 `INIT` 两阶段；引用关系落 `platform_application_reference`，中间件删除入口据此拦截。

**Tech Stack:** Java 21 / Spring Boot 4.1.2 / MyBatis / MySQL / Helm 4.2.0 / Jackson 3 / Vue 3 + Element Plus + `@vue-flow/core`。

**Spec:** [docs/superpowers/specs/2026-10-25-app-orchestration-design.md](../specs/2026-10-25-app-orchestration-design.md)
**Plan①（已完成）:** [2026-10-25-app-orchestration-batch1.md](2026-10-25-app-orchestration-batch1.md)

## Global Constraints

- **spec JSON 是真相源**；中间件的连接信息**只从实例派生**，不抄进 spec（抄了就会与实例漂移）。
- **密码/令牌不进入** spec、revision 快照、渲染清单、日志；注入只允许 `secretKeyRef`。
- **一个应用 = 一个集群 + 一个命名空间**：`BIND` 的实例必须同集群同命名空间；`EXTERNAL` 不受限。
- **中间件节点不参与 SSA/prune**：它由中间件商店的适配器创建与删除，台账只管组件渲染出的对象。
- 新端点必须有权限行；本批**不新增 platform-api 端点**（复用 `/application/*` 与 `/middleware/*`）。
- 本仓没有 Flyway：迁移手工重放；MySQL 注释 `--` 后必须跟空白字符。
- 用户文案一律中文；注释解释「为什么」。

## Review Focus

1. **注入的密码泄漏**：组件 env 里只能出现 `secretKeyRef`，测试要断言渲染结果不含明文（Task 5）。
2. **BIND 了别家/别的命名空间的实例**：必须拒绝，不能只靠前端下拉（Task 6）。
3. **删实例把正在用的中间件删掉**：引用守卫要在 `MiddlewareService.delete` 里拦，且报错列出引用方（Task 7）。
4. **初始化 SQL 重复执行**：Job 必须幂等/可重入，失败重试不能把库搞坏（Task 8）。
5. **中间件创建到一半失败**：已建的实例要保留（用户能重试），不能留下「台账有、集群无」的幽灵（Task 6）。

---

## File Structure

**platform-common**
- Modify `.../models/app/AppNodeDTO.java`（无字段变化，kind 取值扩展）
- Create `.../models/app/AppInjectDTO.java`：`{ String name; String value; String secretName; String secretKey; }`
- Create `.../models/app/AppMiddlewareSpecDTO.java`（节点参数的结构化视图，k8s-server 不直接用，platform-api 用）
- Modify `.../models/middleware/MiddlewareConnectionDTO.java`：加 `ConnInfo info`
- Create `.../models/app/AppInitRequestDTO.java` / `AppInitResultDTO.java`

**platform-data**
- Create `db/migration/V2026_10_26_1__application_middleware.sql`：实例表加 `app_id` / `app_node_id`
- Modify `PlatformApplicationReferenceMapper`（若计划①未建 XML 则补）

**platform-api**
- Create `.../app/AppDependencyResolver.java`（纯函数：图 → 注入计划 + 阶段顺序）
- Create `.../services/ApplicationMiddlewareService.java`（CREATE/BIND/EXTERNAL 的执行与等待就绪）
- Modify `.../services/ApplicationService.java`（create/update 校验中间件节点、写引用台账）
- Modify `.../services/ApplicationDeployService.java`（插入 MIDDLEWARE / INIT 阶段）
- Modify `.../services/MiddlewareService.java`（`delete` 检查引用；`connection` 填 `info`）
- Modify `.../app/AppSpecValidator.java`（kind 扩展、连线规则）

**k8s-server**
- Modify `.../app/AppWorkloadValues.java`（接受注入的 env/secretEnv）
- Modify `.../app/AppRenderService.java`（请求带注入）
- Create `.../app/AppInitService.java` + `controllers/app/AppController` 加 `/application/init`
- Create `.../app/AppInitScripts.java`（每种中间件一个 Job 模板）

**platform-web**
- Modify `ApplicationCanvasView.vue`、`AppNodeInspector.vue`、`AppDeployDrawer.vue`、`types.ts`、`api/index.ts`

**文档**
- Modify `docs/development/application-canvas.md`（计划②的语义）
- Modify `k8s-server/src/main/resources/charts/app/workload/README.md`（注入的 values 形状）

---

### Task 1: 结构化连接信息（`MiddlewareConnectionDTO.info`）

**Files:**
- Modify: `platform-common/src/main/java/com/coding/common/models/middleware/MiddlewareConnectionDTO.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/MiddlewareService.java`（`connection` 方法各 code 分支填 `info`）
- Test: `platform-api/src/test/java/com/coding/platformapi/services/MiddlewareConnectionInfoTest.java`

**Interfaces:**
- Consumes: 既有 `MiddlewareService.connection(instanceId, tenantId)`
- Produces: `MiddlewareConnectionDTO.ConnInfo { String protocol; String host; Integer port; String secretName; String usernameKey; String passwordKey; String databaseKey; Map<String,String> extra; }`

- [ ] **Step 1: Write the failing test**

```java
package com.coding.platformapi.services;

import com.coding.common.models.middleware.MiddlewareConnectionDTO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 连接信息必须同时给「人看的行」和「机器用的结构化字段」，否则注入只能靠猜约定。 */
class MiddlewareConnectionInfoTest {

    @Test
    void connInfoCarriesHostPortAndSecretKeys() {
        MiddlewareConnectionDTO.ConnInfo info = MiddlewareConnectionDTO.ConnInfo
                .of("mysql", "demo-mysql.demo.svc.cluster.local", 3306)
                .secret("demo-mysql-secrets", "root", "root")
                .build();

        assertThat(info.getHost()).isEqualTo("demo-mysql.demo.svc.cluster.local");
        assertThat(info.getPort()).isEqualTo(3306);
        assertThat(info.getSecretName()).isEqualTo("demo-mysql-secrets");
        assertThat(info.getPasswordKey()).isEqualTo("root");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl platform-api -am test -Dtest=MiddlewareConnectionInfoTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 —— `ConnInfo` 不存在。

- [ ] **Step 3: Implement**

在 `MiddlewareConnectionDTO` 里加内部类（Lombok `@Data` + builder 风格静态方法）：

```java
    /** 机器可用的连接信息：注入全靠它，不要在别处再拼一遍约定。 */
    @Data
    public static class ConnInfo {
        private String protocol;      // mysql / redis / kafka / amqp / es
        private String host;
        private Integer port;
        private String secretName;
        private String usernameKey;
        private String passwordKey;
        private String databaseKey;
        private Map<String, String> extra = Map.of();

        public static Builder of(String protocol, String host, Integer port) {
            return new Builder(protocol, host, port);
        }

        public static class Builder {
            private final ConnInfo info = new ConnInfo();

            Builder(String protocol, String host, Integer port) {
                info.protocol = protocol;
                info.host = host;
                info.port = port;
            }

            public Builder secret(String secretName, String usernameKey, String passwordKey) {
                info.secretName = secretName;
                info.usernameKey = usernameKey;
                info.passwordKey = passwordKey;
                return this;
            }

            public Builder databaseKey(String databaseKey) {
                info.databaseKey = databaseKey;
                return this;
            }

            public ConnInfo build() {
                return info;
            }
        }
    }
```

`MiddlewareService.connection` 的每个 `case` 里同时构造 `info` 并 `out.setInfo(info)`：mysql 用 `<name>-haproxy:3306` + Secret `<name>-secrets`（键 `root`）、redis 用当前形态的主地址 + `<name>-credentials/password`、kafka 用 `<name>-kafka-bootstrap:9092`（无凭据）、rabbitmq `<name>:5672` + `<name>-default-user`、postgresql `<name>-rw:5432` + `<name>-app`、elasticsearch `<name>-es-http:9200` + `<name>-es-elastic-user/elastic`、nacos `nacos-cs:8848`（无凭据）、milvus `<name>-milvus:19530` + `<name>-milvus-storage`。

- [ ] **Step 4: Run test**

Run: `mvn -q -pl platform-api -am test -Dtest=MiddlewareConnectionInfoTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add platform-common/src/main/java/com/coding/common/models/middleware/MiddlewareConnectionDTO.java \
        platform-api/src/main/java/com/coding/platformapi/services/MiddlewareService.java \
        platform-api/src/test/java/com/coding/platformapi/services/MiddlewareConnectionInfoTest.java
git commit -m "feat(application): 中间件连接信息提供结构化字段（供注入）"
```

---

### Task 2: 图的 spec 扩展（MIDDLEWARE / EXTERNAL 节点与连线规则）

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/app/AppSpecValidator.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/app/AppSpecValidatorMiddlewareTest.java`

**Interfaces:**
- Consumes: 计划① 的 `AppNodeDTO.kind`
- Produces: 合法 kind 集合 `SERVICE / WORKER / MIDDLEWARE / EXTERNAL`；节点参数约定（见下）

- [ ] **Step 1: Write the failing test**

```java
package com.coding.platformapi.app;

import com.coding.common.models.app.AppEdgeDTO;
import com.coding.common.models.app.AppNodeDTO;
import com.coding.common.models.app.AppSpecDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppSpecValidatorMiddlewareTest {

    private static AppNodeDTO node(String key, String kind, Map<String, Object> params) {
        AppNodeDTO n = new AppNodeDTO();
        n.setKey(key);
        n.setKind(kind);
        n.setParams(params);
        return n;
    }

    private static AppEdgeDTO edge(String from, String to) {
        AppEdgeDTO e = new AppEdgeDTO();
        e.setFrom(from);
        e.setTo(to);
        return e;
    }

    private static AppSpecDTO spec(List<AppNodeDTO> nodes, List<AppEdgeDTO> edges) {
        AppSpecDTO s = new AppSpecDTO();
        s.setNamespace("demo");
        s.setNodes(nodes);
        s.setEdges(edges);
        return s;
    }

    private static List<String> errors(AppSpecDTO s) {
        return AppSpecValidator.validate(s).stream()
                .filter(p -> "ERROR".equals(p.getSeverity()))
                .map(p -> p.getMessage())
                .toList();
    }

    @Test
    void acceptsMiddlewareAndExternalNodes() {
        AppSpecDTO s = spec(List.of(
                node("web", "SERVICE", Map.of("image", "nginx")),
                node("db", "MIDDLEWARE", Map.of("code", "mysql", "provisionMode", "CREATE")),
                node("mq", "MIDDLEWARE", Map.of("code", "kafka", "provisionMode", "EXTERNAL"))),
                List.of(edge("web", "db"), edge("web", "mq")));
        assertThat(errors(s)).isEmpty();
    }

    @Test
    void rejectsMiddlewareMissingCodeOrMode() {
        assertThat(errors(spec(List.of(node("db", "MIDDLEWARE", Map.of())), List.of())))
                .anySatisfy(m -> assertThat(m).contains("中间件节点必须选择中间件类型"));
        assertThat(errors(spec(List.of(node("db", "MIDDLEWARE", Map.of("code", "mysql"))), List.of())))
                .anySatisfy(m -> assertThat(m).contains("必须选择来源"));
        assertThat(errors(spec(List.of(node("db", "MIDDLEWARE",
                Map.of("code", "mysql", "provisionMode", "SOMETHING"))), List.of())))
                .anySatisfy(m -> assertThat(m).contains("不支持的来源方式"));
    }

    @Test
    void rejectsMiddlewareToMiddlewareEdges() {
        AppSpecDTO s = spec(List.of(
                node("db", "MIDDLEWARE", Map.of("code", "mysql", "provisionMode", "CREATE")),
                node("cache", "MIDDLEWARE", Map.of("code", "redis", "provisionMode", "CREATE"))),
                List.of(edge("db", "cache")));
        assertThat(errors(s)).anySatisfy(m -> assertThat(m).contains("中间件之间不能连线"));
    }

    @Test
    void requiresExternalNodesToCarryAddress() {
        assertThat(errors(spec(List.of(node("ext", "EXTERNAL", Map.of("protocol", "mysql"))), List.of())))
                .anySatisfy(m -> assertThat(m).contains("外部服务必须填写地址"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl platform-api -am test -Dtest=AppSpecValidatorMiddlewareTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL —— 目前 `MIDDLEWARE` 被判「不支持的节点类型」。

- [ ] **Step 3: Implement**

`AppSpecValidator`：

```java
    private static final Set<String> KINDS = Set.of("SERVICE", "WORKER", "MIDDLEWARE", "EXTERNAL");
    private static final Set<String> PROVISION_MODES = Set.of("CREATE", "BIND", "EXTERNAL");
    private static final Set<String> MIDDLEWARE_CODES =
            Set.of("mysql", "redis", "kafka", "rabbitmq", "postgresql", "elasticsearch", "nacos", "milvus");
```

节点校验追加（在已有 kind 校验之后）：

```java
            if ("MIDDLEWARE".equals(kind)) {
                Map<String, Object> p = n.getParams() == null ? Map.of() : n.getParams();
                String code = str(p.get("code"));
                if (!MIDDLEWARE_CODES.contains(code)) {
                    out.add(AppProblemDTO.error(key, "中间件节点必须选择中间件类型：" + key));
                }
                String mode = str(p.get("provisionMode"));
                if (mode.isBlank()) {
                    out.add(AppProblemDTO.error(key, "中间件节点必须选择来源：平台创建 / 绑定已有实例 / 链接外部"));
                } else if (!PROVISION_MODES.contains(mode)) {
                    out.add(AppProblemDTO.error(key, "不支持的来源方式：" + mode));
                } else if ("BIND".equals(mode) && str(p.get("instanceId")).isBlank()) {
                    out.add(AppProblemDTO.error(key, "绑定已有实例需要选择一个实例"));
                } else if ("EXTERNAL".equals(mode) && str(p.get("host")).isBlank()) {
                    out.add(AppProblemDTO.error(key, "外部中间件必须填写地址"));
                }
            }
            if ("EXTERNAL".equals(kind)) {
                Map<String, Object> p = n.getParams() == null ? Map.of() : n.getParams();
                if (str(p.get("host")).isBlank()) {
                    out.add(AppProblemDTO.error(key, "外部服务必须填写地址"));
                }
            }
```

连线校验追加（在端点存在性校验之后）：

```java
            String fromKind = byKey.get(from).getKind();
            String toKind = byKey.get(to).getKind();
            if ("MIDDLEWARE".equals(fromKind) && "MIDDLEWARE".equals(toKind)) {
                out.add(AppProblemDTO.error(from, "中间件之间不能连线：" + from + " → " + to));
                continue;
            }
            if ("EXTERNAL".equals(fromKind) || "MIDDLEWARE".equals(fromKind)) {
                // 中间件/外部服务只能被使用，不能去「使用」别人
                out.add(AppProblemDTO.error(from, "只有服务和后台进程可以发起连线：" + from + " → " + to));
                continue;
            }
```

- [ ] **Step 4: Run test**

Run: `mvn -q -pl platform-api -am test -Dtest=AppSpecValidatorMiddlewareTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 4, Failures: 0`。

- [ ] **Step 5: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/app/AppSpecValidator.java \
        platform-api/src/test/java/com/coding/platformapi/app/AppSpecValidatorMiddlewareTest.java
git commit -m "feat(application): 图支持中间件/外部节点与连线规则"
```

---

### Task 3: 迁移（实例来源列 + 引用表）

**Files:**
- Create: `platform-data/src/main/resources/db/migration/V2026_10_26_1__application_middleware.sql`
- Create: `platform-data/src/main/java/com/coding/data/models/system/PlatformApplicationReference.java`
- Create: `platform-data/src/main/java/com/coding/data/mapper/system/PlatformApplicationReferenceMapper.java`
- Create: `platform-data/src/main/resources/mapper/system/PlatformApplicationReferenceMapper.xml`
- Modify: `platform-data/src/main/java/com/coding/data/models/system/PlatformMiddlewareInstance.java`
- Modify: `platform-data/src/main/resources/mapper/system/PlatformMiddlewareInstanceMapper.xml`（列映射与 insert/update）
- Test: `platform-api/src/test/java/com/coding/platformapi/app/ApplicationMiddlewareMigrationTest.java`

**Interfaces:**
- Consumes: 既有 `platform_middleware_instance`
- Produces:
  - 列 `app_id`、`app_node_id`（可空）
  - 表 `platform_application_reference`（**计划①的迁移没有建它**，本批补上）+ 对应 model/mapper
  - `PlatformApplicationReferenceMapper`：`insert` / `batchInsert` / `deleteByApp` / `selectByApp` / `selectByRef`

- [ ] **Step 1: Write the failing test**

```java
package com.coding.platformapi.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationMiddlewareMigrationTest {

    @Test
    void addsApplicationColumnsAndReferenceTable() throws Exception {
        String sql = Files.readString(Path.of(
                "../platform-data/src/main/resources/db/migration/V2026_10_26_1__application_middleware.sql"));
        assertThat(sql).contains("app_id").contains("app_node_id");
        assertThat(sql).contains("information_schema.COLUMNS");
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS platform_application_reference");
        assertThat(sql).contains("ref_type").contains("ref_id");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl platform-api -am test -Dtest=ApplicationMiddlewareMigrationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（文件不存在）。

- [ ] **Step 3: Write the migration**

```sql
-- ============================================================================================
-- 应用编排 · 计划②：中间件实例记录「由哪个应用的哪个节点创建」（2026-10-26）
-- ============================================================================================
-- 只用于展示来源与删除应用时的提示，不改变实例的生命周期（实例仍归中间件商店）。
-- 幂等：条件式 ADD COLUMN；⚠️ 本仓没有 Flyway，手工重放。
-- ============================================================================================

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_middleware_instance'
               AND COLUMN_NAME = 'app_id');
SET @ddl := IF(@col = 0,
  'ALTER TABLE platform_middleware_instance ADD COLUMN app_id varchar(64) NULL COMMENT ''创建它的应用；空 = 中间件商店直接建的''',
  'SET @skip_app_id = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_middleware_instance'
               AND COLUMN_NAME = 'app_node_id');
SET @ddl := IF(@col = 0,
  'ALTER TABLE platform_middleware_instance ADD COLUMN app_node_id varchar(64) NULL COMMENT ''应用里的节点 key''',
  'SET @skip_app_node_id = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

-- 引用台账：谁在用这个中间件实例（删除守卫的反查表）。
-- 计划①的设计文档里列了这张表，但当时的迁移漏了，本批补建。
CREATE TABLE IF NOT EXISTS platform_application_reference (
  id         bigint       NOT NULL AUTO_INCREMENT,
  app_id     varchar(64)  NOT NULL,
  node_id    varchar(64)  NOT NULL COMMENT '应用里的节点 key',
  ref_type   varchar(32)  NOT NULL COMMENT 'MIDDLEWARE_INSTANCE / SECRET',
  ref_id     varchar(64)  NOT NULL,
  created_at datetime     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_ref (app_id, node_id, ref_type, ref_id),
  KEY idx_ref (ref_type, ref_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用对平台对象的引用（删除守卫）';
```

模型加两个字段，mapper XML 的 resultMap / Base_Column_List / insert / updateById 同步补上。

- [ ] **Step 4: Run test**

Run: `mvn -q -pl platform-api -am test -Dtest=ApplicationMiddlewareMigrationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add platform-data/src/main/resources/db/migration/V2026_10_26_1__application_middleware.sql \
        platform-data/src/main/java/com/coding/data/models/system/PlatformMiddlewareInstance.java \
        platform-data/src/main/resources/mapper/system/PlatformMiddlewareInstanceMapper.xml \
        platform-api/src/test/java/com/coding/platformapi/app/ApplicationMiddlewareMigrationTest.java
git commit -m "feat(application): 中间件实例记录来源应用与节点"
```

---

### Task 4: 依赖解析器（图 → 注入计划 + 阶段顺序）

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/app/AppDependencyResolver.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/app/AppDependencyResolverTest.java`

**Interfaces:**
- Consumes: `AppSpecDTO`、`MiddlewareConnectionDTO.ConnInfo`（Task 1）
- Produces:
  - `record Injection(String nodeKey, List<AppInjectDTO> env)`
  - `resolve(AppSpecDTO spec, Map<String, ConnInfo> connByNode) → List<Injection>`：一个组件节点一条，env 里是 `<KEY>_HOST/PORT/USER/PASSWORD/...`
  - `middlewareOrder(AppSpecDTO spec) → List<String>`：中间件节点按 key 稳定排序（v1 串行创建）

- [ ] **Step 1: Write the failing test**

```java
package com.coding.platformapi.app;

import com.coding.common.models.app.AppEdgeDTO;
import com.coding.common.models.app.AppInjectDTO;
import com.coding.common.models.app.AppNodeDTO;
import com.coding.common.models.app.AppSpecDTO;
import com.coding.common.models.middleware.MiddlewareConnectionDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppDependencyResolverTest {

    private static AppNodeDTO node(String key, String kind) {
        AppNodeDTO n = new AppNodeDTO();
        n.setKey(key);
        n.setKind(kind);
        n.setParams(Map.of());
        return n;
    }

    private static AppEdgeDTO edge(String from, String to) {
        AppEdgeDTO e = new AppEdgeDTO();
        e.setFrom(from);
        e.setTo(to);
        return e;
    }

    @Test
    void injectsHostPortUserAndPasswordRef() {
        AppSpecDTO spec = new AppSpecDTO();
        spec.setNodes(List.of(node("backend", "SERVICE"), node("main-db", "MIDDLEWARE")));
        spec.setEdges(List.of(edge("backend", "main-db")));

        MiddlewareConnectionDTO.ConnInfo info = MiddlewareConnectionDTO.ConnInfo
                .of("mysql", "demo-mysql.demo.svc.cluster.local", 3306)
                .secret("demo-mysql-secrets", "root", "root")
                .build();

        List<AppDependencyResolver.Injection> out =
                AppDependencyResolver.resolve(spec, Map.of("main-db", info));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).nodeKey()).isEqualTo("backend");
        Map<String, AppInjectDTO> byName = out.get(0).byName();
        assertThat(byName.get("MAIN_DB_HOST").getValue()).isEqualTo("demo-mysql.demo.svc.cluster.local");
        assertThat(byName.get("MAIN_DB_PORT").getValue()).isEqualTo("3306");
        assertThat(byName.get("MAIN_DB_USER").getValue()).isEqualTo("root");
        assertThat(byName.get("MAIN_DB_PASSWORD").getSecretName()).isEqualTo("demo-mysql-secrets");
        assertThat(byName.get("MAIN_DB_PASSWORD").getSecretKey()).isEqualTo("root");
        assertThat(byName.get("MAIN_DB_PASSWORD").getValue()).isNull();   // 密码永远是引用，不落值
    }

    @Test
    void kafkaWithoutCredentialsInjectsOnlyBootstrap() {
        AppSpecDTO spec = new AppSpecDTO();
        spec.setNodes(List.of(node("backend", "SERVICE"), node("mq", "MIDDLEWARE")));
        spec.setEdges(List.of(edge("backend", "mq")));
        MiddlewareConnectionDTO.ConnInfo info = MiddlewareConnectionDTO.ConnInfo
                .of("kafka", "demo-kafka-kafka-bootstrap.demo.svc.cluster.local", 9092).build();

        Map<String, AppInjectDTO> env = AppDependencyResolver
                .resolve(spec, Map.of("mq", info)).get(0).byName();

        // 变量名统一 <节点KEY>_<协议>_<字段>
        assertThat(env).containsOnlyKeys("MQ_KAFKA_BOOTSTRAP");
        assertThat(env.get("MQ_KAFKA_BOOTSTRAP").getValue())
                .isEqualTo("demo-kafka-kafka-bootstrap.demo.svc.cluster.local:9092");
    }

    @Test
    void middlewareOrderIsStable() {
        AppSpecDTO spec = new AppSpecDTO();
        spec.setNodes(List.of(node("zeta", "MIDDLEWARE"), node("alpha", "MIDDLEWARE"), node("web", "SERVICE")));

        assertThat(AppDependencyResolver.middlewareOrder(spec)).containsExactly("alpha", "zeta");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl platform-api -am test -Dtest=AppDependencyResolverTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（类与 DTO 不存在）。

- [ ] **Step 3: Implement**

先加 `AppInjectDTO`（platform-common）：

```java
@Data
public class AppInjectDTO {
    private String name;
    /** 普通值；与 secretName 互斥 */
    private String value;
    private String secretName;
    private String secretKey;

    public static AppInjectDTO value(String name, String value) {
        AppInjectDTO d = new AppInjectDTO();
        d.setName(name);
        d.setValue(value);
        return d;
    }

    public static AppInjectDTO secret(String name, String secretName, String secretKey) {
        AppInjectDTO d = new AppInjectDTO();
        d.setName(name);
        d.setSecretName(secretName);
        d.setSecretKey(secretKey);
        return d;
    }
}
```

解析器：

```java
public final class AppDependencyResolver {

    public record Injection(String nodeKey, Map<String, AppInjectDTO> byName) {
    }

    private static final Map<String, String> PROTOCOL_LABELS = Map.of(
            "mysql", "MYSQL", "postgresql", "POSTGRES", "redis", "REDIS",
            "kafka", "KAFKA", "rabbitmq", "AMQP", "elasticsearch", "ES", "nacos", "NACOS", "milvus", "MILVUS");

    public static List<Injection> resolve(AppSpecDTO spec, Map<String, ConnInfo> connByNode) {
        Map<String, List<String>> usesByComponent = new HashMap<>();
        for (AppEdgeDTO e : spec.getEdges()) {
            usesByComponent.computeIfAbsent(e.getFrom(), k -> new ArrayList<>()).add(e.getTo());
        }
        List<Injection> out = new ArrayList<>();
        for (AppNodeDTO n : spec.getNodes()) {
            List<String> uses = usesByComponent.getOrDefault(n.getKey(), List.of());
            if (uses.isEmpty()) {
                continue;
            }
            Map<String, AppInjectDTO> env = new LinkedHashMap<>();
            for (String dep : uses) {
                ConnInfo info = connByNode.get(dep);
                if (info != null) {
                    injectInto(env, AppObjectNames.envPrefix(dep), info);
                }
            }
            if (!env.isEmpty()) {
                out.add(new Injection(n.getKey(), env));
            }
        }
        return out;
    }

    static void injectInto(Map<String, AppInjectDTO> env, String prefix, ConnInfo info) {
        String key = prefix + "_" + PROTOCOL_LABELS.getOrDefault(info.getProtocol(), "DB");
        switch (info.getProtocol() == null ? "" : info.getProtocol()) {
            // 客户端要的是 bootstrap.servers：只给一个 BOOTSTRAP，不给 HOST/PORT 免得用户拼错
            case "kafka" -> env.put(key + "_BOOTSTRAP", AppInjectDTO.value(key + "_BOOTSTRAP",
                    info.getHost() + ":" + info.getPort()));
            // HTTP 客户端要完整 URL
            case "elasticsearch", "nacos" -> env.put(key + "_URL", AppInjectDTO.value(key + "_URL",
                    "http://" + info.getHost() + (info.getPort() == null ? "" : ":" + info.getPort())));
            default -> {
                env.put(key + "_HOST", AppInjectDTO.value(key + "_HOST", info.getHost()));
                if (info.getPort() != null) {
                    env.put(key + "_PORT", AppInjectDTO.value(key + "_PORT", String.valueOf(info.getPort())));
                }
            }
        }
        if (info.getUsernameKey() != null && info.getSecretName() != null) {
            env.put(key + "_USER", AppInjectDTO.secret(key + "_USER", info.getSecretName(), info.getUsernameKey()));
        }
        if (info.getPasswordKey() != null && info.getSecretName() != null) {
            env.put(key + "_PASSWORD",
                    AppInjectDTO.secret(key + "_PASSWORD", info.getSecretName(), info.getPasswordKey()));
        }
    }

    public static List<String> middlewareOrder(AppSpecDTO spec) {
        return spec.getNodes().stream()
                .filter(n -> "MIDDLEWARE".equals(n.getKind()))
                .map(AppNodeDTO::getKey)
                .sorted()
                .toList();
    }
}
```

> 变量名统一为 `<节点KEY>_<协议>_<字段>`（如 `MAIN_DB_MYSQL_HOST`）。**不要**再发明第二套命名：
> 组件环境变量里手动引用时，用户在检查器里看到的就是这一组名字。

- [ ] **Step 4: Run test**

Run: `mvn -q -pl platform-api -am test -Dtest=AppDependencyResolverTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 3, Failures: 0`。

- [ ] **Step 5: Commit**

```bash
git add platform-common/src/main/java/com/coding/common/models/app/AppInjectDTO.java \
        platform-api/src/main/java/com/coding/platformapi/app/AppDependencyResolver.java \
        platform-api/src/test/java/com/coding/platformapi/app/AppDependencyResolverTest.java
git commit -m "feat(application): 依赖解析（连线 → 注入变量）"
```

---

### Task 5: 渲染时注入（k8s-server）

**Files:**
- Modify: `platform-common/src/main/java/com/coding/common/models/app/AppRenderRequestDTO.java`（加 `Map<String, List<AppInjectDTO>> injectByNode`）
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/app/AppWorkloadValues.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/app/AppInjectionValuesTest.java`

**Interfaces:**
- Consumes: `AppInjectDTO`（Task 4）
- Produces: `AppWorkloadValues.build(appId, slug, revision, node, inject)` —— 新重载；注入的普通值进 `env`，`secretName` 进 `secretEnv`

- [ ] **Step 1: Write the failing test**

```java
package com.coding.k8sserver.app;

import com.coding.common.models.app.AppInjectDTO;
import com.coding.common.models.app.AppNodeDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppInjectionValuesTest {

    @Test
    @SuppressWarnings("unchecked")
    void injectedSecretNeverCarriesPlaintext() {
        AppNodeDTO n = new AppNodeDTO();
        n.setKey("backend");
        n.setKind("SERVICE");
        n.setParams(Map.of("image", "demo/backend:1"));

        Map<String, Object> values = AppWorkloadValues.build("app_1", "demo", 1, n,
                List.of(AppInjectDTO.value("MAIN_DB_MYSQL_HOST", "demo-mysql.demo.svc"),
                        AppInjectDTO.secret("MAIN_DB_MYSQL_PASSWORD", "demo-mysql-secrets", "root")));

        Map<String, Object> w = (Map<String, Object>) values.get("workload");
        assertThat((List<Object>) w.get("env"))
                .contains(Map.of("name", "MAIN_DB_MYSQL_HOST", "value", "demo-mysql.demo.svc"));
        assertThat((List<Object>) w.get("secretEnv"))
                .contains(Map.of("name", "MAIN_DB_MYSQL_PASSWORD",
                        "secretName", "demo-mysql-secrets", "secretKey", "root"));
        // 任何地方都不能出现明文密码
        assertThat(values.toString()).doesNotContain("password=");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl k8s-server -am test -Dtest=AppInjectionValuesTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（重载不存在 / `injectByNode` 不存在）。

- [ ] **Step 3: Implement**

```java
    public static Map<String, Object> build(String appId, String slug, int revision, AppNodeDTO node) {
        return build(appId, slug, revision, node, List.of());
    }

    public static Map<String, Object> build(String appId, String slug, int revision,
                                            AppNodeDTO node, List<AppInjectDTO> inject) {
        ... 原有逻辑 ...
        List<Map<String, Object>> env = new ArrayList<>(plainEnv(params));
        List<Map<String, Object>> secrets = new ArrayList<>(secretRefs(params));
        for (AppInjectDTO d : inject == null ? List.<AppInjectDTO>of() : inject) {
            if (d.getSecretName() != null && !d.getSecretName().isBlank()) {
                secrets.add(Map.of("name", d.getName(), "secretName", d.getSecretName(),
                        "secretKey", d.getSecretKey() == null ? d.getName() : d.getSecretKey()));
            } else if (d.getValue() != null) {
                env.add(Map.of("name", d.getName(), "value", d.getValue()));
            }
        }
        workload.put("env", env);
        workload.put("secretEnv", secrets);
        ...
    }
```

`AppRenderService.render` 增加：

```java
            List<AppInjectDTO> inject = req.getInjectByNode() == null ? List.of()
                    : req.getInjectByNode().getOrDefault(node.getKey(), List.of());
            values = writeValues(AppWorkloadValues.build(req.getAppId(), req.getAppSlug(),
                    req.getRevision() == null ? 0 : req.getRevision(), node, inject));
```

- [ ] **Step 4: Run test + 既有渲染测试**

Run: `mvn -q -pl k8s-server -am test -Dtest='AppInjectionValuesTest,AppWorkloadValuesTest,AppRenderServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 全绿。

- [ ] **Step 5: Commit**

```bash
git add platform-common/src/main/java/com/coding/common/models/app/AppRenderRequestDTO.java \
        k8s-server/src/main/java/com/coding/k8sserver/app/AppWorkloadValues.java \
        k8s-server/src/main/java/com/coding/k8sserver/app/AppRenderService.java \
        k8s-server/src/test/java/com/coding/k8sserver/app/AppInjectionValuesTest.java
git commit -m "feat(application): 渲染时注入依赖连接信息（密码只走 secretKeyRef）"
```

---

### Task 6: 中间件节点的执行（CREATE / BIND / EXTERNAL）

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/services/ApplicationMiddlewareService.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/ApplicationMiddlewareServiceTest.java`

**Interfaces:**
- Consumes: `MiddlewareService.create(...)` / `connection(...)`、`PlatformMiddlewareInstanceMapper`、`K8sMiddlewareClient.status`
- Produces:
  - `Map<String, ConnInfo> ensure(AppSpecDTO spec, PlatformApplication app, String operator)`
    对每个 MIDDLEWARE 节点返回它的连接信息（CREATE 就地建实例并等 Ready；BIND 校验归属；EXTERNAL 用节点参数）

- [ ] **Step 1: Write the failing test**

```java
package com.coding.platformapi.services;

import com.coding.common.models.app.AppNodeDTO;
import com.coding.common.models.app.AppSpecDTO;
import com.coding.common.models.middleware.MiddlewareConnectionDTO;
import com.coding.data.mapper.system.PlatformMiddlewareInstanceMapper;
import com.coding.data.models.system.PlatformApplication;
import com.coding.data.models.system.PlatformMiddlewareInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApplicationMiddlewareServiceTest {

    private MiddlewareService middleware;
    private PlatformMiddlewareInstanceMapper instances;
    private ApplicationMiddlewareService service;

    @BeforeEach
    void setUp() {
        middleware = mock(MiddlewareService.class);
        instances = mock(PlatformMiddlewareInstanceMapper.class);
        service = new ApplicationMiddlewareService(middleware, instances);
    }

    private static PlatformApplication app() {
        PlatformApplication a = new PlatformApplication();
        a.setId("app_1");
        a.setTenantId("t1");
        a.setClusterId("c1");
        a.setNamespace("demo");
        a.setSlug("demo");
        return a;
    }

    private static AppNodeDTO mw(String key, Map<String, Object> params) {
        AppNodeDTO n = new AppNodeDTO();
        n.setKey(key);
        n.setKind("MIDDLEWARE");
        n.setParams(params);
        return n;
    }

    @Test
    void bindRejectsInstanceFromAnotherNamespace() {
        PlatformMiddlewareInstance foreign = new PlatformMiddlewareInstance();
        foreign.setId("mwi_1");
        foreign.setClusterId("c1");
        foreign.setNamespace("other-team");
        foreign.setTenantId("t1");
        when(instances.selectById("mwi_1")).thenReturn(foreign);

        AppSpecDTO spec = new AppSpecDTO();
        spec.setNodes(List.of(mw("db", Map.of("code", "mysql", "provisionMode", "BIND", "instanceId", "mwi_1"))));

        assertThatThrownBy(() -> service.ensure(spec, app(), "admin"))
                .hasMessageContaining("只能在同一个命名空间");
        verify(middleware, never()).connection(anyString(), any());
    }

    @Test
    void externalUsesNodeParamsWithoutCreatingAnything() {
        AppSpecDTO spec = new AppSpecDTO();
        spec.setNodes(List.of(mw("db", Map.of(
                "code", "mysql", "provisionMode", "EXTERNAL",
                "host", "mysql.corp.internal", "port", 3306,
                "secretName", "corp-db", "usernameKey", "user", "passwordKey", "password"))));

        Map<String, MiddlewareConnectionDTO.ConnInfo> out = service.ensure(spec, app(), "admin");

        assertThat(out.get("db").getHost()).isEqualTo("mysql.corp.internal");
        assertThat(out.get("db").getSecretName()).isEqualTo("corp-db");
        verify(middleware, never()).create(any(), any(), any());
    }

    @Test
    void createCallsMiddlewareStoreAndReturnsConnection() {
        when(middleware.create(any(), anyString(), anyString())).thenReturn(11L);
        PlatformMiddlewareInstance created = new PlatformMiddlewareInstance();
        created.setId("mwi_9");
        created.setClusterId("c1");
        created.setNamespace("demo");
        created.setTenantId("t1");
        created.setCode("mysql");
        created.setName("mw-mysql-1234abcd");
        created.setStatus("READY");
        when(instances.selectLatestByAppNode("app_1", "db")).thenReturn(created);
        when(middleware.statusOf("mwi_9")).thenReturn("READY");
        MiddlewareConnectionDTO conn = new MiddlewareConnectionDTO();
        conn.setInfo(MiddlewareConnectionDTO.ConnInfo.of("mysql", "mw-mysql.demo.svc", 3306)
                .secret("mw-mysql-secrets", "root", "root").build());
        when(middleware.connection("mwi_9", "t1")).thenReturn(conn);

        AppSpecDTO spec = new AppSpecDTO();
        spec.setNodes(List.of(mw("db", Map.of(
                "code", "mysql", "provisionMode", "CREATE", "shape", "SINGLE"))));

        Map<String, MiddlewareConnectionDTO.ConnInfo> out = service.ensure(spec, app(), "admin");

        assertThat(out.get("db").getHost()).isEqualTo("mw-mysql.demo.svc");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl platform-api -am test -Dtest=ApplicationMiddlewareServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: Implement**

```java
@Service
public class ApplicationMiddlewareService {

    private static final long READY_TIMEOUT_MS = 15 * 60 * 1000;

    private final MiddlewareService middleware;
    private final PlatformMiddlewareInstanceMapper instances;

    public ApplicationMiddlewareService(MiddlewareService middleware,
                                        PlatformMiddlewareInstanceMapper instances) {
        this.middleware = middleware;
        this.instances = instances;
    }

    /** 对每个 MIDDLEWARE 节点给出连接信息；CREATE 会就地创建并等 Ready。 */
    public Map<String, ConnInfo> ensure(AppSpecDTO spec, PlatformApplication app, String operator) {
        Map<String, ConnInfo> out = new LinkedHashMap<>();
        for (AppNodeDTO node : spec.getNodes()) {
            if (!"MIDDLEWARE".equals(node.getKind())) {
                continue;
            }
            Map<String, Object> p = node.getParams() == null ? Map.of() : node.getParams();
            String mode = String.valueOf(p.getOrDefault("provisionMode", ""));
            ConnInfo info = switch (mode) {
                case "CREATE" -> create(node, p, app, operator);
                case "BIND" -> bind(p, app);
                case "EXTERNAL" -> external(p);
                default -> throw new CloudPlatformException(EnumResponseType.ERROR,
                        "不支持的来源方式：" + mode + "（节点 " + node.getKey() + "）");
            };
            out.put(node.getKey(), info);
        }
        return out;
    }

    private ConnInfo create(AppNodeDTO node, Map<String, Object> p,
                            PlatformApplication app, String operator) {
        MiddlewareCreateRequest req = new MiddlewareCreateRequest();
        req.setClusterId(app.getClusterId());
        req.setNamespace(app.getNamespace());
        req.setCode(str(p.get("code")));
        req.setMode(str(p.getOrDefault("shape", "SINGLE")));
        req.setDisplayName(app.getSlug() + "-" + node.getKey());
        req.setCpuRequest(strOrNull(p.get("cpuRequest")));
        req.setMemoryRequest(strOrNull(p.get("memoryRequest")));
        req.setStorageSize(strOrNull(p.get("storageSize")));
        req.setMetricsEnabled(Boolean.TRUE.equals(p.get("metricsEnabled")));
        Long opId = middleware.create(req, operator, app.getTenantId());
        // create 内部的实现会写台账；这里按「应用 + 节点」找回刚建的实例
        PlatformMiddlewareInstance created = instances.selectLatestByAppNode(app.getId(), node.getKey());
        if (created == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "中间件实例创建未返回台账记录（操作 " + opId + "）");
        }
        awaitReady(created);
        return middleware.connection(created.getId(), app.getTenantId()).getInfo();
    }

    private ConnInfo bind(Map<String, Object> p, PlatformApplication app) {
        PlatformMiddlewareInstance existing = instances.selectById(str(p.get("instanceId")));
        if (existing == null || existing.getDeletedAt() != null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "要绑定的中间件实例不存在");
        }
        if (!app.getClusterId().equals(existing.getClusterId())
                || !app.getNamespace().equals(existing.getNamespace())) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "只能绑定同一个命名空间里的实例：" + existing.getNamespace());
        }
        if (!app.getTenantId().equals(existing.getTenantId())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "不能绑定别的租户的实例");
        }
        return middleware.connection(existing.getId(), app.getTenantId()).getInfo();
    }

    private ConnInfo external(Map<String, Object> p) {
        return ConnInfo.of(str(p.get("code")), str(p.get("host")),
                        p.get("port") instanceof Number n ? n.intValue() : null)
                .secret(strOrNull(p.get("secretName")), strOrNull(p.get("usernameKey")),
                        strOrNull(p.get("passwordKey")))
                .build();
    }

    /** 轮询到 READY / 超时失败（FAILED 立即失败）。 */
    private void awaitReady(PlatformMiddlewareInstance instance) {
        long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            String status = middleware.statusOf(instance.getId());   // 新增：复用 refreshGroup 的现读
            if ("READY".equals(status)) {
                return;
            }
            if ("FAILED".equals(status)) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "中间件创建失败：" + instance.getDisplayName());
            }
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CloudPlatformException(EnumResponseType.ERROR, "等待中间件就绪被中断");
            }
        }
        throw new CloudPlatformException(EnumResponseType.ERROR,
                "等待中间件就绪超时：" + instance.getDisplayName());
    }
}
```

配套（同一任务内）：
- `MiddlewareService` 新增 `String statusOf(String instanceId)`：按实例查 `client.status(...)` 并回写台账，返回状态字符串（复用 `refreshGroup` 的现读逻辑，抽成一个方法）。
- `PlatformMiddlewareInstanceMapper` 新增 `selectLatestByAppNode(appId, nodeKey)`。
- **`MiddlewareService.create` 增加两个参数/字段**：`appId`、`appNodeId`（`MiddlewareCreateRequest` 加 `appId`/`appNodeId`，写入实例行）。应用内创建时带上；商店直接创建时为 null。
- 创建失败时实例行保持原状（`create` 内部已把失败实例软删），**应用侧不吞异常**：失败即整个部署失败，用户重试时 CREATE 会再建一个新实例（旧失败行已软删，不会幽灵）。

- [ ] **Step 4: Run test**

Run: `mvn -q -pl platform-api -am test -Dtest=ApplicationMiddlewareServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 3, Failures: 0`（`selectLatestByAppNode` 在测试里未打桩时返回 null 的用例需要 `when(...)`）。

- [ ] **Step 5: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/services/ApplicationMiddlewareService.java \
        platform-api/src/main/java/com/coding/platformapi/services/MiddlewareService.java \
        platform-api/src/main/java/com/coding/platformapi/models/MiddlewareCreateRequest.java \
        platform-data/src/main/java/com/coding/data/mapper/system/PlatformMiddlewareInstanceMapper.java \
        platform-data/src/main/resources/mapper/system/PlatformMiddlewareInstanceMapper.xml \
        platform-api/src/test/java/com/coding/platformapi/services/ApplicationMiddlewareServiceTest.java
git commit -m "feat(application): 中间件节点三种来源的执行与就绪等待"
```

---

### Task 7: 引用台账与删除守卫

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/services/ApplicationReferenceGuard.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/ApplicationService.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/MiddlewareService.java`（构造器加一个依赖，`delete` 开头调守卫）
- Test: `platform-api/src/test/java/com/coding/platformapi/services/ApplicationReferenceGuardTest.java`

**Interfaces:**
- Consumes: `PlatformApplicationReferenceMapper`（Task 3 建的表与 mapper）
- Produces:
  - `ApplicationService` 在 create/update 时重建该应用的引用行（`ref_type=MIDDLEWARE_INSTANCE`、`ref_id=实例 id`、`node_id=节点 key`）
  - `MiddlewareService.delete` 开头检查：被引用则抛错并列出应用名

- [ ] **Step 1: Write the failing test**

```java
package com.coding.platformapi.services;

import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.mapper.system.PlatformApplicationReferenceMapper;
import com.coding.data.mapper.system.PlatformMiddlewareInstanceMapper;
import com.coding.data.models.system.PlatformApplication;
import com.coding.data.models.system.PlatformApplicationReference;
import com.coding.data.models.system.PlatformMiddlewareInstance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApplicationReferenceGuardTest {

    @Test
    void refusesWhenInstanceIsUsedByAnApplication() {
        PlatformApplicationReferenceMapper refs = mock(PlatformApplicationReferenceMapper.class);
        PlatformApplicationMapper apps = mock(PlatformApplicationMapper.class);
        ApplicationReferenceGuard guard = new ApplicationReferenceGuard(refs, apps);

        PlatformApplicationReference ref = new PlatformApplicationReference();
        ref.setAppId("app_1");
        PlatformApplication app = new PlatformApplication();
        app.setName("order-service");
        when(refs.selectByRef("MIDDLEWARE_INSTANCE", "mwi_1")).thenReturn(List.of(ref));
        when(apps.selectById("app_1")).thenReturn(app);

        assertThatThrownBy(() -> guard.assertNotReferenced("mwi_1"))
                .hasMessageContaining("被应用 order-service 使用");
    }

    @Test
    void allowsDeletingUnreferencedInstance() {
        PlatformApplicationReferenceMapper refs = mock(PlatformApplicationReferenceMapper.class);
        when(refs.selectByRef("MIDDLEWARE_INSTANCE", "mwi_2")).thenReturn(List.of());

        new ApplicationReferenceGuard(refs, mock(PlatformApplicationMapper.class))
                .assertNotReferenced("mwi_2");
    }
}
```

> 守卫单独成类（与 `NamespaceAccessGuard` 同形）：`MiddlewareService` 有 12 个构造参数，
> 为了测一条规则去拼它既脆又读不懂；守卫只有两个依赖，测试直接 new 即可。

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl platform-api -am test -Dtest=ApplicationReferenceGuardTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（没有守卫；`selectByRef` 不存在）。

- [ ] **Step 3: Implement**

`PlatformApplicationReferenceMapper` 增加：

```java
    int deleteByApp(@Param("appId") String appId);
    List<PlatformApplicationReference> selectByRef(@Param("refType") String refType,
                                                   @Param("refId") String refId);
```

XML：

```xml
<delete id="deleteByApp">
  delete from platform_application_reference where app_id = #{appId,jdbcType=VARCHAR}
</delete>

<select id="selectByRef" resultMap="BaseResultMap">
  select <include refid="Base_Column_List" />
  from platform_application_reference
  where ref_type = #{refType,jdbcType=VARCHAR} and ref_id = #{refId,jdbcType=VARCHAR}
</select>
```

`ApplicationService.update/create`：保存 spec 后调 `rebuildReferences(app)`——先 `deleteByApp`，再把 `MIDDLEWARE` + `provisionMode=BIND` 的实例写成引用行（CREATE 的实例 id 在部署时才产生，由 `ApplicationMiddlewareService` 在 `ensure` 成功后补写；EXTERNAL 不引用平台对象）。

新建守卫：

```java
@Service
public class ApplicationReferenceGuard {

    private final PlatformApplicationReferenceMapper references;
    private final PlatformApplicationMapper applications;

    public ApplicationReferenceGuard(PlatformApplicationReferenceMapper references,
                                     PlatformApplicationMapper applications) {
        this.references = references;
        this.applications = applications;
    }

    /** 被应用引用时拒绝删除，并列出引用它的应用名（用户才知道去哪个画布移除）。 */
    public void assertNotReferenced(String instanceId) {
        List<PlatformApplicationReference> refs =
                references.selectByRef("MIDDLEWARE_INSTANCE", instanceId);
        if (refs.isEmpty()) {
            return;
        }
        String apps = refs.stream().map(r -> {
            PlatformApplication a = applications.selectById(r.getAppId());
            return a == null ? r.getAppId() : a.getName();
        }).distinct().collect(Collectors.joining("、"));
        throw new CloudPlatformException(EnumResponseType.ERROR,
                "该实例被应用 " + apps + " 使用，请先在应用里移除依赖");
    }
}
```

`MiddlewareService.delete` 开头（拿到实例之后、建操作记录之前）调 `referenceGuard.assertNotReferenced(instance.getId())`；
构造器加 `ApplicationReferenceGuard referenceGuard` 参数（Spring 自动注入）。

- [ ] **Step 4: Run test**

Run: `mvn -q -pl platform-api -am test -Dtest=ApplicationReferenceGuardTest -Dsurefire.noSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/services/ApplicationReferenceGuard.java \
        platform-api/src/main/java/com/coding/platformapi/services/ApplicationService.java \
        platform-api/src/main/java/com/coding/platformapi/services/MiddlewareService.java \
        platform-api/src/test/java/com/coding/platformapi/services/ApplicationReferenceGuardTest.java
git commit -m "feat(application): 引用台账与中间件删除守卫"
```

---

### Task 8: 初始化动作（Job 执行）

**Files:**
- Create: `platform-common/src/main/java/com/coding/common/models/app/AppInitRequestDTO.java` / `AppInitResultDTO.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/app/AppInitService.java` · `AppInitScripts.java`
- Modify: `k8s-server/.../controllers/app/AppController.java`（加 `/application/init`）
- Modify: `platform-api/.../services/ApplicationDeployService.java`（INIT 阶段）
- Test: `k8s-server/src/test/java/com/coding/k8sserver/app/AppInitScriptsTest.java`

**Interfaces:**
- Consumes: Kubernetes 集群访问（`KubernetesClientFactory`）、执行器用一个一次性 Job
- Produces:
  - `AppInitRequestDTO { String clusterId; String namespace; String jobName; String code; String image; List<String> args; String secretName; Map<String,String> env; String payload; }`
  - `AppInitService.run(AppInitRequestDTO) → AppInitResultDTO { boolean ok; String message; String logTail; }`
  - `AppInitScripts.jobFor(AppInitRequestDTO) → String manifest`（纯函数，可单测）

- [ ] **Step 1: Write the failing test**

```java
package com.coding.k8sserver.app;

import com.coding.common.models.app.AppInitRequestDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppInitScriptsTest {

    private static AppInitRequestDTO req(String code, String payload) {
        AppInitRequestDTO r = new AppInitRequestDTO();
        r.setClusterId("c1");
        r.setNamespace("demo");
        r.setJobName("demo-db-init-3");
        r.setCode(code);
        r.setSecretName("demo-mysql-secrets");
        r.setPayload(payload);
        r.setEnv(Map.of("HOST", "demo-mysql.demo.svc"));
        return r;
    }

    @Test
    void mysqlJobMountsSqlFromConfigMapAndUsesPasswordFromSecret() {
        String manifest = AppInitScripts.jobFor(req("mysql", "CREATE TABLE IF NOT EXISTS t(id int);"));

        assertThat(manifest).contains("kind: Job").contains("image: mysql:8.4");
        assertThat(manifest).contains("secretKeyRef").contains("demo-mysql-secrets");
        assertThat(manifest).doesNotContain("CREATE TABLE");   // SQL 不在 manifest 里，走 ConfigMap
        assertThat(manifest).contains("demo-db-init-3-sql");
    }

    @Test
    void elasticsearchUsesCurlAndPayloadAsBody() {
        String manifest = AppInitScripts.jobFor(req("elasticsearch", "{\"index\":\"demo\"}"));
        assertThat(manifest).contains("curlimages/curl");
        assertThat(manifest).contains("demo-db-init-3-sql");
    }

    @Test
    void kafkaBuildsTopicCommand() {
        String manifest = AppInitScripts.jobFor(req("kafka", "orders:3:1"));
        assertThat(manifest).contains("kafka-topics.sh").contains("--create").contains("orders");
    }

    @Test
    void unsupportedCodeProducesNoJob() {
        assertThat(AppInitScripts.supports("nacos")).isFalse();
        assertThat(AppInitScripts.supports("mysql")).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl k8s-server -am test -Dtest=AppInitScriptsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败。

- [ ] **Step 3: Implement**

`AppInitScripts` 生成**两份文档**：一个 ConfigMap（放 SQL/命令/JSON）+ 一个 Job（挂载它）。镜像与命令按 code：

| code | 镜像 | 执行 |
|---|---|---|
| mysql | `mysql:8.4` | `mysql -h $HOST -P $PORT -u $USER -p"$PASSWORD" < /init/script.sql` |
| postgresql | `postgres:16` | `PGPASSWORD=$PASSWORD psql -h $HOST -p $PORT -U $USER -f /init/script.sql` |
| redis | `redis:7` | `redis-cli -h $HOST -p $PORT -a "$PASSWORD" < /init/script.txt`（命令序列） |
| elasticsearch | `curlimages/curl:8.10.1` | `curl -sS -X PUT ... -d @/init/script.json`（URL 从 `$HOST`） |
| kafka | `apache/kafka:3.9.0` | 每行 `topic:partitions:replicas` → `kafka-topics.sh --bootstrap-server $BOOTSTRAP --create --if-not-exists ...` |

Job 的规矩（写进代码注释）：`restartPolicy: Never`、`backoffLimit: 0`（失败由平台重试，不要 K8s 自己重试）、
标签 `platform.coding/app` + `app-node` + `managed=true`、`ttlSecondsAfterFinished: 3600`。

`AppInitService.run`：

```java
public AppInitResultDTO run(AppInitRequestDTO req) {
    String manifest = AppInitScripts.manifestFor(req);          // ConfigMap + Job
    HelmResult applied = helm.applyServerSide(clients.adminKubeconfig(req.getClusterId()), manifest);
    if (!applied.ok()) {
        return new AppInitResultDTO(false, "初始化资源创建失败：" + applied.output(), "");
    }
    HelmResult waited = helm.waitJob(clients.adminKubeconfig(req.getClusterId()),
            req.getJobName(), req.getNamespace(), Duration.ofMinutes(10));
    String logs = helm.jobLogs(clients.adminKubeconfig(req.getClusterId()),
            req.getNamespace(), req.getJobName());
    return new AppInitResultDTO(waited.ok(), waited.ok() ? "初始化完成" : waited.output(), logs);
}
```

`HelmRunner` 新增两个方法（与 `waitCrdsEstablished` 同形）：
`waitJob(kubeconfig, name, namespace, timeout)` = `kubectl wait --for=condition=complete job/<name> --timeout=...`；
`jobLogs(kubeconfig, namespace, name)` = `kubectl logs job/<name> --tail=100`。

`DeployService` 在 `MIDDLEWARE` 之后加 `INIT`：

```java
    private String init(PlatformApplicationOperation op) {
        PlatformApplication app = requireApp(op.getAppId(), null);
        PlatformApplicationRevision rev = revisions.selectByRevision(op.getAppId(), op.getRevision());
        Map<String, Object> resolved = readResolved(rev.getResolvedJson());   // 存中间件连接信息
        for (AppNodeDTO node : readSpec(rev.getSpecJson()).getNodes()) {
            if (!"MIDDLEWARE".equals(node.getKind())) continue;
            Map<String, Object> p = node.getParams();
            String payload = str(p.get("initPayload"));
            if (payload == null || payload.isBlank() || !AppInitScripts.supports(str(p.get("code")))) {
                continue;
            }
            AppInitRequestDTO req = buildInit(app, rev, node, payload, resolved);
            AppInitResultDTO r = client.init(req);
            if (!r.isOk()) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "初始化失败（" + node.getKey() + "）：" + r.getMessage());
            }
        }
        return "RENDER";
    }
```

- [ ] **Step 4: Run test**

Run: `mvn -q -pl k8s-server -am test -Dtest=AppInitScriptsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 4, Failures: 0`。

- [ ] **Step 5: Commit**

```bash
git add platform-common/src/main/java/com/coding/common/models/app/AppInit*.java \
        k8s-server/src/main/java/com/coding/k8sserver/app/AppInit*.java \
        k8s-server/src/main/java/com/coding/k8sserver/controllers/app/AppController.java \
        k8s-server/src/main/java/com/coding/k8sserver/helm/HelmRunner.java \
        k8s-server/src/test/java/com/coding/k8sserver/app/AppInitScriptsTest.java \
        platform-api/src/main/java/com/coding/platformapi/services/ApplicationDeployService.java
git commit -m "feat(application): 中间件初始化动作（一次性 Job）"
```

---

### Task 9: 阶段机接入中间件与注入（渲染前置）

**Files:**
- Modify: `platform-api/.../services/ApplicationDeployService.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/ApplicationDeployMiddlewareTest.java`

**Interfaces:**
- Consumes: Task 4/6/7
- Produces: 阶段顺序 `PRECHECK → MIDDLEWARE → INIT → RENDER → CONFLICT → APPLY → PRUNE → HEALTH`

- [ ] **Step 1: Write the failing test**

```java
package com.coding.platformapi.services;

import com.coding.common.models.app.AppNodeDTO;
import com.coding.common.models.app.AppRenderRequestDTO;
import com.coding.common.models.app.AppRenderResultDTO;
import com.coding.common.models.middleware.MiddlewareConnectionDTO;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.mapper.system.PlatformApplicationOperationMapper;
import com.coding.data.mapper.system.PlatformApplicationResourceMapper;
import com.coding.data.mapper.system.PlatformApplicationRevisionMapper;
import com.coding.data.models.system.PlatformApplication;
import com.coding.data.models.system.PlatformApplicationOperation;
import com.coding.data.models.system.PlatformApplicationRevision;
import com.coding.platformapi.k8s.K8sAppClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApplicationDeployMiddlewareTest {

    @Test
    void middlewareStageResolvesConnectionsAndRendersWithInjection() {
        PlatformApplicationMapper apps = mock(PlatformApplicationMapper.class);
        PlatformApplicationRevisionMapper revisions = mock(PlatformApplicationRevisionMapper.class);
        PlatformApplicationOperationMapper operations = mock(PlatformApplicationOperationMapper.class);
        PlatformApplicationResourceMapper resources = mock(PlatformApplicationResourceMapper.class);
        K8sAppClient client = mock(K8sAppClient.class);
        ApplicationMiddlewareService middleware = mock(ApplicationMiddlewareService.class);
        ApplicationDeployCredentials credentials = mock(ApplicationDeployCredentials.class);
        ApplicationDeployService service = new ApplicationDeployService(apps, revisions, operations,
                resources, client, credentials, middleware);

        PlatformApplication app = new PlatformApplication();
        app.setId("app_1");
        app.setTenantId("t1");
        app.setClusterId("c1");
        app.setNamespace("demo");
        app.setSlug("demo");
        app.setCurrentRevision(0);
        when(apps.selectById("app_1")).thenReturn(app);

        PlatformApplicationRevision rev = new PlatformApplicationRevision();
        rev.setAppId("app_1");
        rev.setRevision(1);
        rev.setSpecJson("""
                {"namespace":"demo",
                 "nodes":[{"key":"backend","kind":"SERVICE","params":{"image":"demo/backend:1"}},
                          {"key":"db","kind":"MIDDLEWARE","params":{"code":"mysql","provisionMode":"CREATE"}}],
                 "edges":[{"from":"backend","to":"db"}]}
                """);
        when(revisions.selectByRevision("app_1", 1)).thenReturn(rev);

        MiddlewareConnectionDTO.ConnInfo info = MiddlewareConnectionDTO.ConnInfo
                .of("mysql", "mw-mysql.demo.svc", 3306)
                .secret("mw-mysql-secrets", "root", "root").build();
        when(middleware.ensure(any(), any(), any())).thenReturn(Map.of("db", info));

        AppRenderResultDTO rendered = new AppRenderResultDTO();
        rendered.setManifest("");
        rendered.setObjects(List.of());
        rendered.setProblems(List.of());
        when(client.render(any())).thenReturn(rendered);

        PlatformApplicationOperation op = new PlatformApplicationOperation();
        op.setId(7L);
        op.setAppId("app_1");
        op.setRevision(1);
        op.setStatus("RUNNING");
        op.setCurrentStep("MIDDLEWARE");
        op.setStartedAt(new Date());

        service.advance(op);

        ArgumentCaptor<AppRenderRequestDTO> cap = ArgumentCaptor.forClass(AppRenderRequestDTO.class);
        verify(client).render(cap.capture());
        assertThat(cap.getValue().getInjectByNode()).containsKey("backend");
        assertThat(cap.getValue().getInjectByNode().get("backend"))
                .anySatisfy(d -> assertThat(d.getName()).isEqualTo("DB_MYSQL_HOST"));
        assertThat(rev.getResolvedJson()).contains("mw-mysql.demo.svc");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl platform-api -am test -Dtest=ApplicationDeployMiddlewareTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（阶段还是旧顺序，没有注入）。

- [ ] **Step 3: Implement**

`STEPS` 改为 `PRECHECK, MIDDLEWARE, INIT, RENDER, CONFLICT, APPLY, PRUNE, HEALTH`；
`ApplicationDeployService` 的构造器加 `ApplicationMiddlewareService middlewareService`（Spring 注入），
并给没有中间件节点的应用保留一条捷径（直接跳到 RENDER）。
`MIDDLEWARE` 阶段：

```java
    private String middleware(PlatformApplicationOperation op) {
        PlatformApplication app = requireApp(op.getAppId(), null);
        PlatformApplicationRevision rev = revisions.selectByRevision(op.getAppId(), op.getRevision());
        AppSpecDTO spec = readSpec(rev.getSpecJson());
        if (spec.getNodes().stream().noneMatch(n -> "MIDDLEWARE".equals(n.getKind()))) {
            return "RENDER";          // 没有中间件节点，直接渲染
        }
        Map<String, ConnInfo> conns = middlewareService.ensure(spec, app, op.getCreatedBy());
        rev.setResolvedJson(JSON.writeValueAsString(conns));   // 只存 host/port/secret 键，无密码
        revisions.updateById(rev);
        return "INIT";
    }
```

`RENDER` 阶段在构造请求时：

```java
        Map<String, ConnInfo> conns = readResolved(rev.getResolvedJson());
        req.setInjectByNode(AppDependencyResolver.resolve(spec, conns).stream()
                .collect(Collectors.toMap(AppDependencyResolver.Injection::nodeKey,
                        i -> new ArrayList<>(i.byName().values()))));
```

`ApplicationMiddlewareService.ensure` 成功创建实例后补写引用行（app_id / node_id / instanceId）。

- [ ] **Step 4: Run test + 既有部署测试**

Run: `mvn -q -pl platform-api -am test -Dtest='ApplicationDeployMiddlewareTest,ApplicationDeployServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 全绿（既有用例要补 mock 的 `ApplicationMiddlewareService`）。

- [ ] **Step 5: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/services/ApplicationDeployService.java \
        platform-api/src/main/java/com/coding/platformapi/services/ApplicationMiddlewareService.java \
        platform-api/src/test/java/com/coding/platformapi/services/ApplicationDeployMiddlewareTest.java \
        platform-api/src/test/java/com/coding/platformapi/services/ApplicationDeployServiceTest.java
git commit -m "feat(application): 部署阶段插入中间件与初始化，渲染前完成注入"
```

---

### Task 10: 前端、预览与文档

**Files:**
- Modify: `platform-web/src/views/ApplicationCanvasView.vue`、`AppNodeInspector.vue`、`AppDeployDrawer.vue`
- Modify: `platform-web/src/types.ts`（`AppNodeKind` 加 `MIDDLEWARE` / `EXTERNAL`；参数类型）
- Modify: `platform-api/.../services/ApplicationPreviewService.java`（预览返回注入变量摘要）
- Modify: `docs/development/application-canvas.md`

- [ ] **Step 1: 前端节点面板**

左侧加「中间件」「外部服务」两个按钮；`defaultParams`：

```ts
function defaultParams(kind: AppNodeKind): Record<string, unknown> {
  if (kind === 'MIDDLEWARE') {
    return { code: 'mysql', provisionMode: 'CREATE', shape: 'SINGLE', initPayload: '' }
  }
  if (kind === 'EXTERNAL') {
    return { code: 'mysql', host: '', port: 3306, secretName: '', usernameKey: '', passwordKey: '' }
  }
  return kind === 'SERVICE'
    ? { image: '', port: 8080, replicas: 1, expose: 'none', env: [] }
    : { image: '', replicas: 1, env: [] }
}
```

连线校验：只有 SERVICE/WORKER 能作为起点，MIDDLEWARE/EXTERNAL 作为终点（与后端规则一致，前端只做提示）。

- [ ] **Step 2: 检查器**

MIDDLEWARE 节点表单：
- 中间件类型（下拉，来自 `/middleware/catalog`）
- 来源方式：平台创建 / 绑定已有实例 / 链接外部
  - 平台创建：形态（来自 catalog 的 modes）、资源、存储、指标开关、初始化脚本（textarea，按 code 给占位提示）
  - 绑定已有实例：实例下拉（本命名空间、本租户；来自 `/middleware/instance/list`）
  - 链接外部：地址、端口、Secret 名、用户名键、密码键
EXTERNAL 节点：协议、地址、端口、Secret 名与键。

- [ ] **Step 3: 预览与部署抽屉**

- 预览弹窗加一块「注入变量」：每个组件节点列出将要注入的变量名（值用 `******` 掩码，Secret 引用显示 `Secret/键`）；
- 部署抽屉的阶段列表加「中间件」「初始化」两项。

- [ ] **Step 4: 预览后端**

`ApplicationPreviewService`：调 `ApplicationMiddlewareService.previewConnections(spec, app)`（只对 BIND/EXTERNAL 解析，CREATE 不建实例，返回「将创建 <code>」），把注入变量摘要放进 `ApplicationPreview.injections`（新增字段 `List<InjectionView>`）。

- [ ] **Step 5: 构建 + 文档**

```bash
cd platform-web && npm run build
```

`docs/development/application-canvas.md` 补：中间件节点三种来源、注入变量命名、初始化支持的 code 与 payload 形状、
引用守卫、以及「中间件节点不进 prune」。

- [ ] **Step 6: Commit**

```bash
git add platform-web/src platform-api/src/main/java/com/coding/platformapi/services/ApplicationPreviewService.java \
        platform-api/src/main/java/com/coding/platformapi/models/ApplicationPreview.java \
        docs/development/application-canvas.md
git commit -m "feat(web): 画布支持中间件/外部节点与注入预览"
```

---

## 计划②完成标准

- 画布能画「前端 + 后端 + MySQL + Kafka（外部）」，连线后组件自动拿到 `*_HOST/PORT/USER/PASSWORD`；
- 平台创建的中间件在「中间件实例」页可见并标注来源应用；绑定/外部不产生实例；
- 部署顺序：中间件先起 → 初始化脚本跑完 → 组件创建；初始化失败整体失败且可重试；
- 被应用引用的实例在实例页删除时被拒，并列出引用它的应用；
- 渲染清单里没有任何明文密码；
- 全量测试与前端构建绿。
