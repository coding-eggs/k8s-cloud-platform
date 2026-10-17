package com.coding.platformapi.services;

import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.k8s.PlatformAlertEventMapper;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.data.models.k8s.PlatformAlertEvent;
import com.coding.data.models.k8s.PlatformAlertEventQuery;
import com.coding.data.models.system.TokenUserInfo;
import com.coding.platformapi.models.AlertQueryRequest;
import com.coding.platformapi.models.AlertWebhookRequest;
import com.coding.platformapi.security.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警列表。
 *
 * <p><b>租户边界在这一层</b>（照 {@code NamespaceAllocationService.list} 的口径）：
 * token 带 tenantInfo 时**入参一概不算数**，只按该租户在本集群的命名空间分配去查；
 * 没有租户 claim（平台管理员）才不过滤。
 *
 * <p>⚠️ 租户在本集群一个命名空间都没分配 → 传**空 list**（不是 null）。
 * 空 list 在 mapper 里变成 {@code and 1 = 0}，返回空集；传 null 会退化成"不过滤"，
 * 等于让这个租户看到全平台的告警。
 */
@Service
public class AlertService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    /** 固定值：自检造的告警用这个 alertname，便于筛出来与清理。 */
    static final String SELF_TEST_ALERTNAME = "PlatformSelfTest";

    private final PlatformAlertEventMapper mapper;
    private final PlatformTenantNamespaceMapper allocs;
    private final AuthContext authContext;

    /** 自检用的回环客户端工厂（每次调用新建一个，避免把连接状态带进长活 bean）。 */
    private final RestClient.Builder restClientBuilder;

    private final String webhookToken;
    private final int serverPort;

    /**
     * 生产构造器。
     *
     * <p>⚠️ 不注入 {@link RestClient.Builder} —— 本应用里没有那个 bean（同 {@code PromRulesClient} 的坑）。
     * 本类有两个构造器，故显式 {@code @Autowired}。
     */
    @Autowired
    public AlertService(PlatformAlertEventMapper mapper,
                        PlatformTenantNamespaceMapper allocs,
                        AuthContext authContext,
                        @Value("${platform.alert.webhook.token}") String webhookToken,
                        @Value("${server.port}") int serverPort) {
        this(mapper, allocs, authContext, RestClient.builder(), webhookToken, serverPort);
    }

    /** 测试专用：传入已 bind 了 {@code MockRestServiceServer} 的 builder。 */
    AlertService(PlatformAlertEventMapper mapper,
                 PlatformTenantNamespaceMapper allocs,
                 AuthContext authContext,
                 RestClient.Builder restClientBuilder,
                 String webhookToken,
                 int serverPort) {
        this.mapper = mapper;
        this.allocs = allocs;
        this.authContext = authContext;
        this.restClientBuilder = restClientBuilder;
        this.webhookToken = webhookToken;
        this.serverPort = serverPort;
    }

    public List<PlatformAlertEvent> list(AlertQueryRequest req) {
        PlatformAlertEventQuery q = new PlatformAlertEventQuery();
        q.setClusterId(req.getClusterId());
        q.setFrom(req.getFrom());
        q.setTo(req.getTo());
        q.setNamespace(req.getNamespace());
        q.setStatus(req.getStatus());
        q.setSeverity(req.getSeverity());
        q.setAlertname(req.getAlertname());
        q.setExcludeWatchdog(!Boolean.TRUE.equals(req.getShowWatchdog()));   // 默认排除
        q.setCursorStartsAt(req.getCursorStartsAt());
        q.setCursorId(req.getCursorId());
        q.setLimit(clamp(req.getLimit()));

        TokenUserInfo info = authContext.current();
        String tokenTenantId = (info != null && info.getTenantInfo() != null)
                ? info.getTenantInfo().getTenantId() : null;
        if (StringUtils.hasText(tokenTenantId)) {
            List<String> ns = allocs.listByTenantAndCluster(tokenTenantId, req.getClusterId()).stream()
                    .map(PlatformTenantNamespace::getNamespace)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
            q.setNamespaces(ns);
        }
        // 无租户 claim（平台管理员）→ namespaces 保持 null = 不过滤
        return mapper.query(q);
    }

    private static int clamp(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * 自检：往**自己的回环地址**投一条合成告警（firing + resolved），走完整的
     * HTTP → 放行 → 密钥校验 → 解析 → 落库链路。
     *
     * <p>为什么要它：集群侧配好 Alertmanager 之前，验收「链路通不通」只能等真告警。
     * 这个端点让平台能自证 —— 而且它验的是**平台这一侧**的全部环节，与集群那一侧正交。
     *
     * <p>⚠️ 刻意打**回环地址**（127.0.0.1:server.port）而不是直接调 ingest service：
     * 后者会把 HTTP 层、放行、密钥校验、body 解析全部绕过 —— 而那几层恰恰是最容易配错的。
     */
    public SelfTestResult selfTest(String clusterId) {
        String url = "http://127.0.0.1:" + serverPort + "/alert/webhook?clusterId=" + clusterId;
        List<AlertWebhookRequest.Alert> payload = buildSelfTestAlerts(clusterId);
        int status;
        try {
            status = restClientBuilder.build().post().uri(url)
                    .header("Authorization", "Bearer " + webhookToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .exchange((req, res) -> res.getStatusCode().value());
        } catch (Exception e) {
            return new SelfTestResult(-1, 0, List.of(), "回环请求失败：" + e.getMessage());
        }
        List<Long> ids = mapper.findIdsByAlertname(clusterId, SELF_TEST_ALERTNAME);
        if (ids == null) {
            ids = List.of();
        }
        return new SelfTestResult(status, ids.size(), ids, null);
    }

    /** 物理删。幂等 —— 已经没了也返回成功（清测试数据时连着点两下不该报错）。 */
    public void delete(long id) {
        mapper.deleteById(id);
    }

    /** 自检结果：三个数字定位链路断在哪一层。 */
    public record SelfTestResult(int status, int rowCount, List<Long> ids, String error) {}

    /**
     * 造两条自检告警：**resolved 在前、firing 在后**，startsAt 相差 1 秒 ——
     * 同一个 fingerprint 的两个会话，正好把幂等键的另一半也验了。
     * <p>{@code labels.namespace} 取该集群任一真实分配；一个都没有就留空（空 namespace 也要能落库）。
     */
    private List<AlertWebhookRequest.Alert> buildSelfTestAlerts(String clusterId) {
        String namespace = firstAllocatedNamespace(clusterId);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        List<AlertWebhookRequest.Alert> out = new ArrayList<>();
        out.add(selfTestAlert("resolved", namespace, now, now.plus(1, ChronoUnit.MINUTES)));
        out.add(selfTestAlert("firing", namespace, now.plus(1, ChronoUnit.SECONDS), null));
        return out;
    }

    private String firstAllocatedNamespace(String clusterId) {
        try {
            List<PlatformTenantNamespace> rows = allocs.listByCluster(clusterId);
            if (rows != null) {
                for (PlatformTenantNamespace r : rows) {
                    if (StringUtils.hasText(r.getNamespace())) {
                        return r.getNamespace();
                    }
                }
            }
        } catch (Exception e) {
            // 反查失败不该让自检连第一步都走不到 —— 没有 namespace 也能落库
        }
        return null;
    }

    private static AlertWebhookRequest.Alert selfTestAlert(String state, String namespace,
                                                           Instant startsAt, Instant endsAt) {
        AlertWebhookRequest.Status st = new AlertWebhookRequest.Status();
        st.setState(state);

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("alertname", SELF_TEST_ALERTNAME);
        labels.put("severity", "info");
        if (StringUtils.hasText(namespace)) {
            labels.put("namespace", namespace);
        }

        AlertWebhookRequest.Alert a = new AlertWebhookRequest.Alert();
        a.setStatus(st);
        a.setLabels(labels);
        a.setAnnotations(Map.of("summary", "平台告警链路自检"));
        a.setStartsAt(startsAt.toString());
        if (endsAt != null) {
            a.setEndsAt(endsAt.toString());
        }
        a.setFingerprint("platform-self-test");
        return a;
    }
}
