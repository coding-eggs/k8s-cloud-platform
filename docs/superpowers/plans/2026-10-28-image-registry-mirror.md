# 镜像来源纳管（平台 Harbor / 官方）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 集群组件、中间件与平台自身镜像都能从平台 Harbor（`platform` 项目）拉取，组件/中间件表单默认用它、可切回官方。

**Architecture:** 平台不搬运镜像（Harbor 复制规则负责），只在**渲染 values / 拼 CR 时改写镜像地址**，并保证目标命名空间有 `imagePullSecret`（补到 `default` ServiceAccount 上，chart 不用改）。镜像地址规则是一个纯函数；配置分「平台默认 + 按集群覆盖」两层。

**Tech Stack:** Java 21 / Spring Boot 4（platform-api、k8s-server）、MyBatis（platform-data）、Vue 3 + Element Plus（platform-web）、Harbor v2 API（已装组件 `harbor`）。

**Spec:** [2026-10-28-image-registry-mirror-design.md](../specs/2026-10-28-image-registry-mirror-design.md)

## Global Constraints

- **镜像命名**（spec §5）：`<harborHost>/<project>/<上游主机>/<仓库路径>[:tag|@digest]`；
  无主机名的引用先归一化到 `docker.io`（单段仓库补 `library/`）。
- **本环境实际参数**（2026-10-28 更新）：
  - Harbor 入口：**`https://harbor.ackcp.cloud`**（DigiCert 公信证书，2026-10-28 → 2027-01-25；
    证书与私钥在仓库外的 `cert/harbor.ackcp.cloud.pem` / `.key`，**已被 .gitignore 挡住，绝不入库**）；续期见
    [2026-10-28-harbor-tls-renewal-design.md](../specs/2026-10-28-harbor-tls-renewal-design.md)；
    实际域名由运维填进配置，本文用 `<域名>` 占位）
  - 项目：`platform`（已存在，私有，空）；`library` 是公开项目
  - **端口形态（本批，2026-10-28 定为 B 方案）**：Harbor 走 `expose.type=route`，
    TLS 在 `istio-system/external-gateway` 的 **443 / HTTPS listener** 终止，镜像地址**不带端口**：
    `harbor.ackcp.cloud/platform/...`
  - **前置**：集群必须已装 istio（与 grafana / 平台入口同一条路，平台已有这个前置检查）
  - 节点侧前提：能解析 `harbor.ackcp.cloud` → Harbor 入口 IP（**节点自己的解析器**，不是 CoreDNS）
  - **节点不需要任何 containerd 改动**：公信证书在默认根池里，节点只要能解析该域名即可
  - 平台后端调 Harbor API 的 scheme 从写死的 `http://` 改成配置（默认 `https`），见 Task 3
- **不改写就等价于今天的行为**：`imageSource=OFFICIAL` 或 `platform.image-registry.enabled=false` 时，代码路径完全不变。
- **Harbor 自身的镜像永远走官方**（先有鸡还是先有蛋），安装 `harbor` 组件时强制忽略 `PLATFORM`。
- **新端点必须有权限行**（否则 `PermissionCrossCheckRunner` 启动失败）——本批新增 `/components/images`。
- 迁移一律手工重放（本仓没有 Flyway）。

## Review Focus

- **改写误伤**：values 里 `repository` 也可能是非镜像字段（如 Git 仓库地址）；`tag`/`digest` 这类键不能碰。
- **嵌套遗漏**：不同 chart 的 image 位置不同（`image.repository` / `image` / `global.imageRegistry` /
  `kibana.image`…），递归漏一层就是「拉一半镜像走公网」。
- **Sub-chart 与 hooks**：cert-manager 的 `startupapicheck`、prometheus 的 admission webhook 等 Job 镜像也要改。
- **最后一块拼图是 k8s-server 自己**：它是写 pull secret 的执行者，必须先把自己那 4 个镜像推上去。
- **私有项目 + 证书到期**：拉取失败的表现都是 `ImagePullBackOff`（`unauthorized` / `certificate has expired`），
  不是安装失败 —— 错误信息要能引导人区分这两条。

---

## Task 1: 迁移（集群列 + 镜像清单权限）

**Files:**
- Create: `platform-data/src/main/resources/db/migration/V2026_10_28_1__image_registry.sql`
- Test: `platform-api/src/test/java/com/coding/platformapi/security/ImageRegistryPermissionMigrationTest.java`

**Interfaces:**
- Produces: `k8s_cluster.image_registry_host` / `image_registry_project`（可空）；权限行
  `/components/images` → `platform:component:read`（与其它组件只读端点同码）。

- [ ] **Step 1: 写失败测试**

```java
class ImageRegistryPermissionMigrationTest {
    private static final Path SQL = Path.of(
            "../platform-data/src/main/resources/db/migration/V2026_10_28_1__image_registry.sql");

    @Test
    void imageListEndpointHasAPermissionRowBoundToAdmin() throws Exception {
        String sql = Files.readString(SQL);
        assertThat(sql).contains("'/components/images'").contains("'platform:component:read'")
                .contains("'builtin_role_admin'");
    }
}
```

- [ ] **Step 2: 跑它，确认失败**

Run: `mvn -o -pl platform-api -am test -Dtest=ImageRegistryPermissionMigrationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（文件不存在）

- [ ] **Step 3: 写迁移**

```sql
-- 镜像来源：按集群覆盖平台默认（空 = 用 application.yaml 的平台默认）
ALTER TABLE k8s_cluster
  ADD COLUMN image_registry_host    VARCHAR(255) NULL COMMENT '镜像仓库地址 host:port（空=平台默认）',
  ADD COLUMN image_registry_project VARCHAR(64)  NULL COMMENT '镜像项目（空=平台默认 platform）';

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_components_images','API','/components/images','POST','platform:component:read','组件/中间件的镜像清单')
ON DUPLICATE KEY UPDATE code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_components_images','builtin_role_admin','perm_components_images')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
```

- [ ] **Step 4: 再跑，确认通过**

Run: 同 Step 2 → Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add platform-data/src/main/resources/db/migration/V2026_10_28_1__image_registry.sql \
        platform-api/src/test/java/com/coding/platformapi/security/ImageRegistryPermissionMigrationTest.java
git commit -m "feat(images): 集群镜像仓库列 + 镜像清单端点权限"
```

---

## Task 1b: Harbor 组件支持可选 HTTPS（域名固定 + 证书由平台写 Secret）

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/models/HarborInstallRequest.java`
  （加 `https`、`tlsCert`、`tlsKey`、`httpsNodePort`）
- Modify: `platform-api/.../services/ComponentInstallService.java`（`applyHarborValues` 生成 tls/ports/externalURL）
- Modify: `platform-common/.../k8s/dto/HarborSecretsRequestDTO.java`（可选带 cert/key）
- Modify: `k8s-server/.../controllers/cluster/ComponentInstallController.java` + `components/HarborSecrets.java`
  （写 `harbor/harbor-tls`，键 `tls.crt` / `tls.key`，幂等 SSA）
- Test: `platform-api/src/test/java/com/coding/platformapi/services/ComponentInstallServiceTest.java`

**Interfaces:**
- Produces：`applyHarborValues` 在 `https=true` 时
  ```yaml
  expose:
    type: route
    tls: { enabled: false }              # TLS 在网关终止，Harbor 自身仍是 HTTP
    route:
      parentRefs: [{ name: external-gateway, namespace: istio-system, sectionName: harbor-https }]
      hosts: [harbor.ackcp.cloud]
  externalURL: https://harbor.ackcp.cloud
  ```
  平台另外保证：`external-gateway` 上有 `harbor-https` listener（443 / HTTPS / Terminate，
  `certificateRefs` → `istio-system/harbor-tls`）。

- [ ] **Step 1: 写失败测试**

```java
@Test
void harborHttpsRendersTlsAndHttpsExternalUrl() {
    HarborInstallRequest h = new HarborInstallRequest("nodePort", "harbor.example.com", 30002, 80);
    h.setHttps(true);
    h.setHttpsNodePort(30443);
    // 断言 applyComponent 的 values：expose.tls.enabled=true、certSource=secret、
    // secretName=harbor-tls、ports.https.nodePort=30443、externalURL=https://harbor.example.com
}

@Test
void harborHttpsRequiresADomain() {
    HarborInstallRequest h = new HarborInstallRequest("nodePort", "192.168.85.180", 30002, 80);
    h.setHttps(true);
    // 直接拒：「HTTPS 必须用域名（证书只对域名有效）」
}
```

- [ ] **Step 2: 跑 → FAIL**
- [ ] **Step 3: 实现**（本环境取值：`host=harbor.ackcp.cloud`、
  证书 = `cert/harbor.ackcp.cloud.pem`（含中间证书的 fullchain）+ `cert/harbor.ackcp.cloud.key`）
  - **证书 Secret 必须放 `istio-system`**：Gateway API 的 `certificateRefs` 只能引用与 Gateway
    同命名空间的 Secret（跨命名空间要 ReferenceGrant，没必要）；名字用 `harbor-tls`
  - **listener 上 `sectionName: harbor-https`**：不写的话 HTTPRoute 会挂到这个 Gateway 的**所有**
    HTTP listener 上（80 / 9527 / 5601…），Harbor 就在明文 80 上也可达了
  - 前置检查：`https=true` 且 expose=route 时，若集群没有 `external-gateway` 直接拒
    （「Harbor 的 HTTPS 依赖 istio 的 external-gateway」）
  - 校验：`https=true` 时 `host` 不得是 IP（`InetAddressUtil`/正则）；`tlsCert`/`tlsKey` 至少有一个非空
    **或**集群里已存在 `harbor-tls`（编辑重放时允许留空沿用）
  - `applyHarborValues`：按上面的结构写 values；`externalURL` 的 scheme 跟 `https` 走
  - `run()` 里 helm 之前调 k8s-server 写 `harbor-tls`（证书/私钥走请求体，**不落 values / 不进快照**）
- [ ] **Step 4: 跑 → PASS（原有 http 用例不回归）**
- [ ] **Step 5: 提交**

> 首装顺序：Certificate 形态的 Secret 必须先存在，否则 `harbor-nginx` 起不来 —— 平台在 helm 之前写，
> 天然满足；失败时整条操作记 FAILED，不会留半个 Harbor。

## Task 2: `ImageReference` —— 归一化与改写（纯函数）

**Files:**
- Create: `platform-common/src/main/java/com/coding/common/models/image/ImageReference.java`
- Test: `platform-common/src/test/java/com/coding/common/models/image/ImageReferenceTest.java`

**Interfaces:**
- Produces:
  - `static ImageReference parse(String image)` → 记录 `(registry, repository, tagOrDigest, original)`
  - `String mirror(String harborHost, String project)` → 镜像地址；已在该 registry 下时**原样返回**（幂等）
  - `boolean looksLikeImage(String value)`（给 values 递归用）

- [ ] **Step 1: 写失败测试**

```java
class ImageReferenceTest {
    @Test
    void normalizesDockerHubShorthand() {
        assertThat(ImageReference.parse("redis:7.2").mirror("h:30002", "platform"))
                .isEqualTo("h:30002/platform/docker.io/library/redis:7.2");
        assertThat(ImageReference.parse("prometheus/prometheus:v2").mirror("h:30002", "platform"))
                .isEqualTo("h:30002/platform/docker.io/prometheus/prometheus:v2");
    }

    @Test
    void keepsUpstreamHostAndTag() {
        assertThat(ImageReference.parse("quay.io/opstree/redis:v7.0.15").mirror("h:30002", "platform"))
                .isEqualTo("h:30002/platform/quay.io/opstree/redis:v7.0.15");
        assertThat(ImageReference.parse("docker.elastic.co/elasticsearch/elasticsearch:9.5.0")
                .mirror("h:30002", "platform"))
                .isEqualTo("h:30002/platform/docker.elastic.co/elasticsearch/elasticsearch:9.5.0");
    }

    @Test
    void keepsDigestAndIsIdempotent() {
        String once = ImageReference.parse("quay.io/a/b@sha256:" + "a".repeat(64))
                .mirror("h:30002", "platform");
        assertThat(once).endsWith("@sha256:" + "a".repeat(64));
        assertThat(ImageReference.parse(once).mirror("h:30002", "platform")).isEqualTo(once);
    }

    @Test
    void doesNotTreatUrlsOrPathsAsImages() {
        assertThat(ImageReference.looksLikeImage("https://get.infini.cloud/x")).isFalse();
        assertThat(ImageReference.looksLikeImage("/var/tempo/traces")).isFalse();
        assertThat(ImageReference.looksLikeImage("always")).isFalse();
        assertThat(ImageReference.looksLikeImage("quay.io/opstree/redis:v7.0.15")).isTrue();
    }
}
```

- [ ] **Step 2: 跑它，确认失败**（`找不到符号 ImageReference`）

- [ ] **Step 3: 实现**

```java
public record ImageReference(String registry, String repository, String tagOrDigest, String original) {

    private static final Set<String> KNOWN_HOST_HINTS = Set.of("docker.io", "quay.io", "ghcr.io",
            "registry.k8s.io", "docker.elastic.co", "gcr.io");

    public static ImageReference parse(String image) {
        String s = image.trim();
        String tag = "";
        int at = s.indexOf('@');
        if (at >= 0) { tag = s.substring(at); s = s.substring(0, at); }
        else {
            int colon = s.lastIndexOf(':');
            if (colon > s.lastIndexOf('/')) { tag = s.substring(colon); s = s.substring(0, colon); }
        }
        String first = s.contains("/") ? s.substring(0, s.indexOf('/')) : "";
        boolean host = first.contains(".") || first.contains(":") || first.equals("localhost");
        String registry = host ? first : "docker.io";
        String repo = host ? s.substring(first.length() + 1) : s;
        if ("docker.io".equals(registry) && !repo.contains("/")) { repo = "library/" + repo; }
        return new ImageReference(registry, repo, tag, image);
    }

    public String mirror(String harborHost, String project) {
        if (registry.equals(harborHost)) { return original; }   // 幂等
        return harborHost + "/" + project + "/" + registry + "/" + repository + tagOrDigest;
    }

    /** values 递归用：像镜像引用才改写 */
    public static boolean looksLikeImage(String v) {
        if (v == null || v.isBlank() || v.contains(" ") || v.contains("://")) { return false; }
        if (v.startsWith("/") || v.startsWith(".")) { return false; }
        ImageReference r = parse(v);
        boolean knownHost = KNOWN_HOST_HINTS.contains(r.registry()) || r.registry().contains(".")
                || r.registry().contains(":");
        boolean hasRepo = r.repository().contains("/") || r.repository().matches("[a-z0-9._-]+");
        boolean hasTag = !r.tagOrDigest().isEmpty();
        return hasRepo && (hasTag || knownHost);   // 有 tag 或有 registry 才算镜像
    }
}
```

- [ ] **Step 4: 再跑 → PASS**

- [ ] **Step 5: 提交**：`git commit -m "feat(images): ImageReference 归一化/改写纯函数"`

---

## Task 3: 配置解析（平台默认 + 集群覆盖 + 来源判定）

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/images/ImageRegistryConfig.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/images/ImageRegistryService.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/ClusterService.java`（读两列）
- Test: `platform-api/src/test/java/com/coding/platformapi/images/ImageRegistryServiceTest.java`

**Interfaces:**
- Produces:
  - `record ImageRegistry(String host, String project, boolean enabled)`；
  - `ImageRegistry resolve(String clusterId)` → **集群列 > Harbor 安装记录的 externalURL > 平台默认**；
    `enabled=false` 或地址为空时返回 `ImageRegistry.OFFICIAL`
  - `String harborBaseUrl(String clusterId)` → 带 scheme 的 Harbor API 地址（供 `HarborRegistryService` 用，
    取代写死的 `http://`）
  - `String rewrite(String clusterId, String image, ImageSource source)` — `PLATFORM` 且 enabled 才改写
  - enum `ImageSource { PLATFORM, OFFICIAL }`

- [ ] **Step 1: 写失败测试**

```java
class ImageRegistryServiceTest {
    // clusterService 用 mock；properties 用 setter 构造
    @Test
    void clusterColumnsWinOverPlatformDefaults() {
        when(cluster.imageRegistryHost("c1")).thenReturn("10.0.0.9:30002");
        when(cluster.imageRegistryProject("c1")).thenReturn(null);
        var svc = new ImageRegistryService(cluster, props("h:30002", "platform", true));
        assertThat(svc.resolve("c1")).extracting(ImageRegistry::host, ImageRegistry::project)
                .containsExactly("10.0.0.9:30002", "platform");
    }

    @Test
    void disabledOrUnsetMeansNoRewrite() {
        var svc = new ImageRegistryService(cluster, props("", "platform", true));
        assertThat(svc.resolve("c1")).isEqualTo(ImageRegistry.OFFICIAL);
        assertThat(svc.rewrite("c1", "redis:7", ImageSource.PLATFORM)).isEqualTo("redis:7");
    }

    @Test
    void platformSourceRewritesAndOfficialLeavesAsIs() {
        var svc = new ImageRegistryService(cluster, props("h:30002", "platform", true));
        assertThat(svc.rewrite("c1", "redis:7", ImageSource.PLATFORM))
                .isEqualTo("h:30002/platform/docker.io/library/redis:7");
        assertThat(svc.rewrite("c1", "redis:7", ImageSource.OFFICIAL)).isEqualTo("redis:7");
    }
}
```

- [ ] **Step 2: 跑 → FAIL（类不存在）**
- [ ] **Step 3: 实现**
  - `ClusterService` 加两个查询方法读新列；
  - **地址优先取 Harbor 组件的安装记录**（Task 1b 的 `externalURL`，含 scheme）：`HarborRegistryService.harborHost()`
    改为直接返回 `externalURL`（去尾斜杠），不再拼 `"http://"`；`harborBaseUrl()` 用它；
  - `HarborDialect` 对无 scheme 端点补 `https://` 的兜底保留（老快照没有 scheme 时仍然能用），但**平台自己
    产生的地址一律带 scheme**；
  - 镜像引用本身不带 scheme（`域名[:端口]/...`），所以 Task 2 的映射函数不受影响。
- [ ] **Step 4: 跑 → PASS**
- [ ] **Step 5: 提交**

---

## Task 4: `ImageRewriter`（values 递归）＋ 全 chart 护栏测试

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/images/ImageRewriter.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/images/ImageRewriterTest.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/images/ImageCatalogGuardTest.java`

**Interfaces:**
- Consumes: `ImageReference`
- Produces: `static Object rewrite(Object node, String harborHost, String project)` —— 深拷贝改写，
  只认键 `image` / `repository` / `imageName` / `imageRepository`（值用 `looksLikeImage` 判定）；
  `registry` 键（`global.imageRegistry`）单独换成 `harborHost/project`。

- [ ] **Step 1: 写失败测试**

```java
class ImageRewriterTest {
    @Test
    void rewritesNestedImageFields() {
        Map<String, Object> values = Map.of(
                "image", Map.of("repository", "quay.io/opstree/redis", "tag", "v7.0.15"),
                "sidecar", Map.of("image", "quay.io/prometheuscommunity/elasticsearch-exporter:v1.7.0"));
        String out = ImageRewriter.rewrite(values, "h:30002", "platform").toString();
        assertThat(out).contains("h:30002/platform/quay.io/opstree/redis")
                .contains("h:30002/platform/quay.io/prometheuscommunity/elasticsearch-exporter:v1.7.0");
    }

    @Test
    void leavesNonImageValuesAlone() {
        Map<String, Object> values = Map.of(
                "imagePullPolicy", "IfNotPresent",
                "gitRepo", "https://gitee.com/x/y.git",
                "path", "/var/tempo/traces");
        assertThat(ImageRewriter.rewrite(values, "h:30002", "platform")).isEqualTo(values);
    }

    @Test
    void repositoryPlusTagIsJoinedBeforeRewrite() {
        Map<String, Object> values = Map.of("image", Map.of(
                "repository", "docker.elastic.co/elasticsearch/elasticsearch", "tag", "9.5.0"));
        assertThat(ImageRewriter.rewrite(values, "h:30002", "platform").toString())
                .contains("h:30002/platform/docker.elastic.co/elasticsearch/elasticsearch:9.5.0");
    }
}
```

> `repository` + `tag` 分开写是 chart 的常见形态：先把同层的 `repository`/`tag` 拼成完整引用再判定与改写，
> 改完只写回 `repository`（`tag` 保持不动）。这是本任务最容易写错的地方。

- [ ] **Step 2: 跑 → FAIL**
- [ ] **Step 3: 实现**（递归 `Map`/`List`；进入 `image` map 时先拼 `repository`+`tag`；`registry` 键特殊处理）
- [ ] **Step 4: 跑 → PASS**
- [ ] **Step 5: 写护栏测试**（关键）

```java
class ImageCatalogGuardTest {
    /** 内置 chart 的默认 values 全过一遍：改写后不允许残留任何上游主机名 */
    @ParameterizedTest
    @MethodSource("chartValues")
    void noUpstreamRegistrySurvivesRewrite(Map<String, Object> values) {
        String out = ImageRewriter.rewrite(values, "h:30002", "platform").toString();
        for (String host : List.of("quay.io/", "ghcr.io/", "registry.k8s.io/",
                "docker.elastic.co/", "gcr.io/")) {
            assertThat(out).doesNotContain(host);
        }
        // docker.io 只在已经被 platform 前缀包着的地方出现（platform/docker.io/...）
        assertThat(out.replace("platform/docker.io/", "")).doesNotContain("docker.io/");
    }
}
```

`chartValues()` 用 `ComponentRecipeCatalog` + 中间件 `MiddlewareOperatorCatalog` 的 release 列表扫
`charts/{components,middleware}/**/chart/values.yaml`（`SnakeYAML` 读成 Map）。跑不过就说明漏了字段，
回到实现补规则（**不要**把断言放宽）。

- [ ] **Step 6: 跑全量测试并提交**

---

## Task 5: 组件安装接入（请求字段 + 改写调用 + 快照）

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/models/ComponentApplyRequest.java`（加 `imageSource`）
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/ComponentInstallService.java`
  （`apply()` 里 `resolveValues` 之后调用改写；`snapshotWithPlatformInputs` 落 `imageSource`；
   `harbor` 组件强制 `OFFICIAL`）
- Test: `platform-api/src/test/java/com/coding/platformapi/services/ComponentInstallServiceTest.java`

**Interfaces:**
- Consumes: `ImageRegistryService.rewrite`
- Produces: 下发到 k8s-server 的 `ReleaseInstallSpecDTO.values` 中镜像已改写；快照多一个 `imageSource`。

- [ ] **Step 1: 写失败测试**

```java
@Test
void componentValuesPointAtPlatformHarborByDefault() {
    // MANAGEMENT profile + metrics-server（镜像少）
    when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
    ...
    stubImageRegistry("h:30002", "platform", true);
    ComponentApplyRequest req = new ComponentApplyRequest("c1", "metrics-server", "INSTALL", null, Map.of());
    service().apply(req, "admin");
    ArgumentCaptor<List<ReleaseInstallSpecDTO>> captor = ...;
    assertThat(captor.getValue().toString()).contains("h:30002/platform/registry.k8s.io/metrics-server/metrics-server")
            .doesNotContain("registry.k8s.io/metrics-server/metrics-server:");
}

@Test
void harborComponentAlwaysUsesOfficialImages() {
    // 即使 imageSource=PLATFORM，harbor 的 values 里 docker.io/goharbor/... 也不能被改写
}
```

- [ ] **Step 2: 跑 → FAIL**
- [ ] **Step 3: 实现**
  - `ComponentApplyRequest` 加 `private String imageSource;`（缺省 null = PLATFORM）
  - `apply()` 里 `List<ReleaseSpec> effective = resolveValues(...)` 之后：
    ```java
    List<ReleaseSpec> finalRelease = "harbor".equals(req.getComponentCode())
            ? effective                                     // harbor 自身必须走官方（spec §10）
            : imageRewriter.rewriteReleases(effective, req.getClusterId(), imageSource(req));
    ```
    `rewriteReleases` 只改 `contractValues()`（渲染用那份），不动 `values()` 原始参数。
  - 快照：`snapshotWithPlatformInputs(...)` 加 `imageSource` 键。
- [ ] **Step 4: 跑 → PASS（含既有组件用例不回归）**
- [ ] **Step 5: 提交**

---

## Task 6: 中间件（DTO 传 registry + 7 家镜像点位）

**Files:**
- Modify: `platform-common/src/main/java/com/coding/common/models/middleware/MiddlewareCreateRequestDTO.java`
  （加 `imageRegistryHost` / `imageRegistryProject`，可空）
- Modify: `platform-api/.../services/MiddlewareService.java`（创建/重放时填这两个字段，来源同 Task 5）
- Modify: `k8s-server/.../middleware/`：`ElasticsearchMiddlewareAdapter`、`RedisMiddlewareAdapter`、
  `KafkaMiddlewareAdapter`、`MysqlMiddlewareAdapter`、`MilvusMiddlewareAdapter`、
  `RabbitmqMiddlewareAdapter`、`PostgresqlMiddlewareAdapter`（各自 set image 的地方）
- Create: `k8s-server/src/main/java/com/coding/k8sserver/middleware/Images.java`
- Test: 各适配器测试 + `ImagesTest`

**Interfaces:**
- Produces: `Images.resolve(String image, MiddlewareCreateRequestDTO req)` —— 两个字段都空时原样返回。

- [ ] **Step 1: 写失败测试**

```java
class ImagesTest {
    @Test
    void rewritesWhenRegistryGiven() {
        var req = new MiddlewareCreateRequestDTO();
        req.setImageRegistryHost("h:30002");
        req.setImageRegistryProject("platform");
        assertThat(Images.resolve("quay.io/opstree/redis:v7.0.15", req))
                .isEqualTo("h:30002/platform/quay.io/opstree/redis:v7.0.15");
    }

    @Test
    void leavesImageAloneWithoutRegistry() {
        assertThat(Images.resolve("quay.io/opstree/redis:v7.0.15", new MiddlewareCreateRequestDTO()))
                .isEqualTo("quay.io/opstree/redis:v7.0.15");
    }
}
```

适配器侧各加一条：`esAdapter.buildCreate(reqWithRegistry)` 的主镜像与 exporter 边车都在 Harbor 下。

- [ ] **Step 2: 跑 → FAIL**
- [ ] **Step 3: 实现**：`Images.resolve` 复用 `ImageReference`（platform-common 里，k8s-server 能直接引）；
  各适配器把 `DEFAULT_*_IMAGE` 常量改成 `Images.resolve(CONST, req)`。ES 的 IK init 容器复用主镜像，自动跟随。
- [ ] **Step 4: 跑 → PASS**
- [ ] **Step 5: 提交**

---

## Task 7: k8s-server 下发 imagePullSecret（并补 default SA）

**Files:**
- Create: `k8s-server/src/main/java/com/coding/k8sserver/components/ImagePullSecrets.java`
- Create: `platform-common/src/main/java/com/coding/common/models/k8s/dto/ImagePullSecretRequestDTO.java`
- Modify: `k8s-server/.../controllers/cluster/ComponentInstallController.java`（`POST /components/image-pull-secret`）
- Modify: `platform-api/.../k8s/K8sComponentClient.java`（同名方法）
- Test: `k8s-server/src/test/java/com/coding/k8sserver/components/ImagePullSecretsTest.java`

**Interfaces:**
- Consumes: Harbor 凭据（Task 8 产出）
- Produces:
  - DTO：`{clusterId, namespaces[], secretName="platform-harbor-pull", registry, username, password}`
  - `ImagePullSecrets.dockerConfigJson(registry, username, password)` → `.dockerconfigjson` 字符串（纯函数，可单测）

- [ ] **Step 1: 写失败测试**

```java
class ImagePullSecretsTest {
    @Test
    void buildsDockerConfigJson() {
        String json = ImagePullSecrets.dockerConfigJson("h:30002", "robot$x", "p@ss");
        assertThat(json).contains("\"h:30002\"").contains("robot$x");
    }
}
```

- [ ] **Step 2: 跑 → FAIL**
- [ ] **Step 3: 实现**
  - `ImagePullSecrets.build(secretName, ns, registry, user, pass)` 返回 `Secret`（type `kubernetes.io/dockerconfigjson`）
  - 控制器：SSA 写 Secret → 读 `default` SA → 把 secretName 合并进 `imagePullSecrets`（去重）→ SSA 写回 SA
  - 幂等：同一 ns 重复调用不重复追加
- [ ] **Step 4: 跑 → PASS**
- [ ] **Step 5: platform-api 在组件安装/中间件创建前调用**（`run()` 开头，命名空间取 release ns + 实例 ns）
- [ ] **Step 6: 提交**

---

## Task 8: Harbor `platform` 项目的拉取 robot

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/HarborRegistryService.java`
  （新增 `ensurePlatformPullRobot(clusterId)`：`platform` 项目不存在则建、robot 存在则复用、
  凭据存 `CredentialService`，`SOURCE=HARBOR`、scope=`platform-pull`）
- Modify: `platform-api/src/main/java/com/coding/platformapi/images/ImageRegistryService.java`
  （改写启用时取该凭据，供 Task 7 的 DTO 用）
- Test: `HarborRegistryServiceTest`

**Interfaces:**
- Produces: `Optional<Credential> platformPullCredential(String clusterId)`（user/pass）

- [ ] **Step 1: 写失败测试**（Harbor 用 mock：项目已存在→不建；robot 已存在→不重建、不轮换口令）
- [ ] **Step 2: 跑 → FAIL**
- [ ] **Step 3: 实现**（复用 `HarborAdminClient` 的 `createProjectIfAbsent` / `createRobot` / `listRobots`，
  名字用 `HarborNaming` 的 `platform` 规则；robot 权限只有 `pull` on `platform/*`）
- [ ] **Step 4: 跑 → PASS**
- [ ] **Step 5: 真集群验一次**：`curl -u 'robot$platform-pull:<secret>' http://192.168.85.180:30002/v2/_catalog`
  → 200（未推镜像时返回空仓库列表）
- [ ] **Step 6: 提交**

---

## Task 9: 前端（来源选择 + 集群设置 + 镜像清单）

**Files:**
- Modify: `platform-web/src/components/cluster/ClusterComponentsPanel.vue`（表单加「镜像来源」单选：平台 Harbor / 官方）
- Modify: `platform-web/src/views/MiddlewareStoreView.vue`（创建表单同款单选）
- Modify: `platform-web/src/views/ClusterEditView.vue`（或集群设置抽屉：Harbor 地址 / 项目两栏）
- Modify: `platform-web/src/types.ts`、`platform-web/src/api/index.ts`
- Test: `npm run build`（含 `vue-tsc`）—— 本仓前端无单测框架，靠类型检查 + 预览验证

- [ ] **Step 1: 类型与默认值**：`imageSource?: 'PLATFORM' | 'OFFICIAL'`，默认 `'PLATFORM'`；
  未配置 Harbor（后端 bootstrap/集群详情里 `imageRegistry.host` 为空）时选项置灰并提示「先配置 Harbor 地址」
- [ ] **Step 2: 表单接线**（payload 带 `imageSource`；编辑时回填上次快照）
- [ ] **Step 3: 镜像清单页签**（组件页抽屉：表格 `组件 / 上游镜像 / Harbor 地址`，一键复制）
- [ ] **Step 4: `npm run build` 通过**
- [ ] **Step 5: 提交**

---

## Task 10: 镜像清单端点 + 文档

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/models/ImageListItemDTO.java`
- Modify: `platform-api/.../controllers/ComponentController.java`（`POST /components/images`）
- Modify: `platform-api/.../services/ComponentInstallService.java`（清单 = 内置 chart 默认 values 扫描 + 适配器常量）
- Create: `docs/development/image-registry.md`（运维手册：Harbor 复制规则、containerd insecure、
  robot、平台自身镜像的推送顺序、故障排查）
- Test: `ComponentInstallServiceTest.imageListCoversEveryBuiltInChart`

- [ ] **Step 1: 写失败测试**：清单里至少包含 `metrics-server`、`prometheus`、`thanos`、`elasticsearch`、
  `kafka` 五项，且每项的 `mirrored` 都带 `platform/<上游主机>/...`
- [ ] **Step 2: 跑 → FAIL**
- [ ] **Step 3: 实现端点 + 文档**
- [ ] **Step 4: 跑 → PASS；`mvn -o test` 全绿**
- [ ] **Step 5: 提交**

---

## Task 10b: 镜像同步（Harbor 复制规则 + 首次全量 + 核对）

**Files:**
- Create: `docs/development/image-registry.md` 的「同步」章节（与 Task 10 同一份文档）
- 运维动作（不在代码里）：Harbor 6 条 replication rule + 首次触发

- [ ] **Step 1: 文档写清 6 条规则的字段**（每条：远端仓库地址、访问凭据、**Destination namespace = 上游主机名**、
  触发方式=手动、带宽/覆盖策略）
- [ ] **Step 2: 首次全量同步**：按 `/components/images` 的清单逐个触发（Harbor 复制的「复制」按钮），
  或用 Harbor 的「批量复制」
- [ ] **Step 3: 核对脚本**：对清单里每条镜像 `GET /v2/<repo>/manifests/<tag>`，200 即到位；
  把结果贴进文档的「同步记录」
- [ ] **Step 4: 提交文档**

## Task 11: 真集群验证（验收清单）

- [ ] 节点能解析 Harbor 域名，且 `crictl pull <域名>/library/hello-world:latest` 成功（公信证书，无需改 containerd）
  - ⚠️ 2026-10-28 实测：公网权威 DNS 已有记录（223.5.5.5 / 8.8.8.8 都能查到 `192.168.31.61/62/80`），
    但**内网解析器拒答**（路由器 `Query refused`，集群 Pod 里 `getent hosts` 同样失败）——
    上机前必须先在**内网 DNS 加一条 A 记录**（或每台节点 `/etc/hosts`），否则拉镜像 `no such host`
- [ ] 平台后端能调通 Harbor API：`https://<域名>/api/v2.0/projects` 用 admin 凭据返回 200
- [ ] Harbor `platform` 项目里已有试点镜像（先用 `metrics-server` 一个）
- [ ] 平台上对 `metrics-server` 选「平台 Harbor」重装 → Pod 从 `<域名>/platform/registry.k8s.io/...` 拉起
- [ ] 切回「官方」重装 → 镜像地址回到 `registry.k8s.io/...`
- [ ] 中间件：新建一个 Redis 实例（镜像最少），确认 CR 里 `image` 指向 Harbor
- [ ] 断网模拟（可选）：临时阻断节点出网，重装一个组件仍成功
- [ ] 记录：`docs/development/image-registry.md` 补一节「本次验证结果」

---

## 实施顺序建议

1 → **1b（Harbor HTTPS）** → 2 → 3 → 4（护栏）→ 5（组件）→ 7+8（凭据）→ 6（中间件）→ 9（前端）→
10（清单/文档）→ **10b（镜像同步）** → 11（验收）。

4 之前不要动 `ComponentInstallService`：护栏测试是后面所有改写的安全网。

## 风险与回退

- 任一环节出问题：`platform.image-registry.enabled=false`（或表单选「官方」）即回到现状，无需回滚代码。
- Harbor 现在走域名 + 公信证书，节点侧没有额外配置；剩下的运维风险是**证书到期**（3 个月期），
  自动续期方案见 [2026-10-28-harbor-tls-renewal-design.md](../specs/2026-10-28-harbor-tls-renewal-design.md)，
  不随本批实施。
