package com.coding.platformapi.app;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OTel 接入「开关 → Pod 模板注解 / 容器 env / 日志卷」的唯一计算（纯函数）。
 *
 * <p>两处共用：工作负载页「可观测接入」（{@code WorkloadService.observability}，读线上对象改写）与
 * 应用服务的 {@code desired.otel}（发布渲染）。规则改一处，两边同时生效。
 *
 * <p>语言 / 组合 → Instrumentation：只追踪 {@code platform-instrumentation}、只应用日志
 * {@code platform-instrumentation-logs}、两者都要 {@code platform-instrumentation-all}；文件日志走 sidecar，
 * 不开应用日志 appender。采样率写 {@code OTEL_TRACES_SAMPLER} + {@code _ARG}（必须同时写：operator 发现容器里
 * 已有 {@code OTEL_TRACES_SAMPLER} 就整块跳过注入）。
 */
public final class OtelInjection {

    public static final String NAMESPACE = "monitor";
    public static final String INJECT_PREFIX = "instrumentation.opentelemetry.io/inject-";
    /** 语言 → 注入注解键；第一批只支持 java / python */
    public static final Map<String, String> INJECT_KEYS = Map.of(
            "java", INJECT_PREFIX + "java",
            "python", INJECT_PREFIX + "python");
    public static final String SERVICE_NAME_ENV = "OTEL_SERVICE_NAME";
    public static final String SAMPLER_ENV = "OTEL_TRACES_SAMPLER";
    public static final String SAMPLER_ARG_ENV = "OTEL_TRACES_SAMPLER_ARG";
    public static final String SAMPLER_TYPE = "parentbased_traceidratio";
    public static final String SIDECAR_ANNOTATION = "sidecar.opentelemetry.io/inject";
    public static final String SIDECAR_VALUE = NAMESPACE + "/platform-otel-sidecar";
    /** sidecar 侧要采的文件（sidecar 把共享卷挂在 /var/log/app） */
    public static final String LOG_FILE_ANNOTATION = "platform.coding/otel-log-file";
    public static final String LOG_VOLUME = "platform-otel-logs";
    public static final String LOG_MODE_FILE = "file";
    private static final String SIDECAR_LOG_DIR = "/var/log/app/";

    /**
     * @param podAnnotations 要写上的注解（关闭 = 空）
     * @param env            要写到主容器的 env（name → value，保持插入顺序）
     * @param logMountDir    文件日志：应用容器里日志文件的父目录（挂 emptyDir）；null = 不需要日志卷
     */
    public record Plan(Map<String, String> podAnnotations, Map<String, String> env, String logMountDir) {
    }

    private OtelInjection() {
    }

    public static Plan plan(String language, boolean tracing, boolean logs, String logMode, String logFile,
                            Integer sampling, String serviceName) {
        boolean fileLogs = logs && LOG_MODE_FILE.equalsIgnoreCase(logMode);
        // 应用日志（agent appender）才需要 Instrumentation；文件日志靠 sidecar 注入
        boolean agentEnabled = tracing || (logs && !fileLogs);
        String lang = language == null ? null : language.trim().toLowerCase();
        if (agentEnabled && !INJECT_KEYS.containsKey(lang)) {
            throw bad("不支持的应用类型：" + language + "（第一批只支持 java / python）");
        }
        // 文件日志指定**具体文件**（不是整个目录）：平台把父目录挂成 emptyDir，sidecar 只采这一个文件
        String logDir = null;
        String logFileName = null;
        if (fileLogs) {
            String file = logFile == null ? "" : logFile.trim();
            int slash = file.lastIndexOf('/');
            if (!file.startsWith("/") || slash <= 0) {
                throw bad("日志文件必须是容器内的绝对路径（以 / 开头）");
            }
            if (slash == file.length() - 1) {
                throw bad("请填到具体日志文件（如 /var/log/nginx/access.log），不要填目录");
            }
            logDir = file.substring(0, slash);
            logFileName = file.substring(slash + 1);
        }
        if (sampling != null && (sampling < 1 || sampling > 100)) {
            throw bad("采样率必须在 1..100 之间（收到 " + sampling + "）");
        }

        Map<String, String> annotations = new LinkedHashMap<>();
        if (agentEnabled) {
            String instrumentation = tracing && logs && !fileLogs
                    ? "platform-instrumentation-all"
                    : tracing ? "platform-instrumentation" : "platform-instrumentation-logs";
            annotations.put(INJECT_KEYS.get(lang), NAMESPACE + "/" + instrumentation);
        }
        if (fileLogs) {
            annotations.put(SIDECAR_ANNOTATION, SIDECAR_VALUE);
            annotations.put(LOG_FILE_ANNOTATION, SIDECAR_LOG_DIR + logFileName);
        }
        Map<String, String> env = new LinkedHashMap<>();
        if (agentEnabled) {
            env.put(SERVICE_NAME_ENV, serviceName);
        }
        if (tracing && sampling != null) {
            env.put(SAMPLER_ENV, SAMPLER_TYPE);
            env.put(SAMPLER_ARG_ENV, samplingRatio(sampling));
        }
        return new Plan(annotations, env, logDir);
    }

    /** 20 → "0.2"、1 → "0.01"、100 → "1"（BigDecimal 免得出现 0.30000000000000004） */
    static String samplingRatio(int percent) {
        return java.math.BigDecimal.valueOf(percent).movePointLeft(2).stripTrailingZeros().toPlainString();
    }

    private static CloudPlatformException bad(String message) {
        return new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, message);
    }
}
