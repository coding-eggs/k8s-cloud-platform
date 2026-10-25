package com.coding.k8sserver.app;

import com.coding.common.models.app.AppInitRequestDTO;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 中间件初始化的一次性 Job 模板。
 *
 * <p>规矩：<b>脚本正文一律进 ConfigMap</b>，Job 清单里只引用它 —— 这样 SQL 不会出现在
 * 操作记录/审计/日志的清单片段里；凭据一律 {@code secretKeyRef}。
 *
 * <p>{@code backoffLimit: 0}：失败由平台重试（用户能看到日志再决定），不让 K8s 自己反复跑 SQL。
 */
public final class AppInitScripts {

    private static final Set<String> SUPPORTED =
            Set.of("mysql", "postgresql", "redis", "elasticsearch", "kafka");

    private AppInitScripts() {
    }

    public static boolean supports(String code) {
        return code != null && SUPPORTED.contains(code);
    }

    /** ConfigMap 名：带 Job 名（含版本号），重试时不撞名。 */
    public static String configMapName(AppInitRequestDTO req) {
        return req.getJobName() + "-sql";
    }

    public static String configMapFor(AppInitRequestDTO req) {
        return """
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: %s
                  namespace: %s
                  labels:
                    platform.coding/managed: "true"
                data:
                  payload: |
                %s
                """.formatted(configMapName(req), req.getNamespace(), indent(req.getPayload(), 4));
    }

    public static String jobFor(AppInitRequestDTO req) {
        String image;
        String shell;
        switch (req.getCode() == null ? "" : req.getCode()) {
            case "mysql" -> {
                image = "mysql:8.4";
                shell = "mysql -h \"$HOST\" -P \"${PORT:-3306}\" -u \"${USER_NAME:-root}\" < /init/payload";
            }
            case "postgresql" -> {
                image = "postgres:16";
                shell = "psql -h \"$HOST\" -p \"${PORT:-5432}\" -U \"$USER_NAME\" -d postgres -f /init/payload";
            }
            case "redis" -> {
                image = "redis:7";
                shell = "redis-cli -h \"$HOST\" -p \"${PORT:-6379}\" --no-auth-warning -a \"$PASSWORD\" < /init/payload";
            }
            case "elasticsearch" -> {
                image = "curlimages/curl:8.10.1";
                shell = "curl -sS -u \"elastic:$PASSWORD\" -X PUT \"$URL$PATH\" "
                        + "-H 'Content-Type: application/json' -d @/init/payload";
            }
            case "kafka" -> {
                image = "apache/kafka:3.9.0";
                // 每行 topic:partitions:replicas；--if-not-exists 保证可重试
                shell = "while IFS=: read -r t p r; do "
                        + "[ -z \"$t\" ] && continue; "
                        + "kafka-topics.sh --bootstrap-server \"$BOOTSTRAP\" --create --if-not-exists "
                        + "--topic \"$t\" --partitions \"${p:-1}\" --replication-factor \"${r:-1}\"; "
                        + "done < /init/payload";
            }
            default -> throw new IllegalArgumentException("不支持初始化：" + req.getCode());
        }

        return """
                apiVersion: batch/v1
                kind: Job
                metadata:
                  name: %s
                  namespace: %s
                  labels:
                    platform.coding/managed: "true"
                spec:
                  backoffLimit: 0
                  ttlSecondsAfterFinished: 3600
                  template:
                    metadata:
                      labels:
                        platform.coding/managed: "true"
                    spec:
                      restartPolicy: Never
                      containers:
                        - name: init
                          image: %s
                          command: ["/bin/sh", "-c"]
                          args:
                            - %s
                %s        volumeMounts:
                          - name: script
                            mountPath: /init
                      volumes:
                        - name: script
                          configMap:
                            name: %s
                """.formatted(req.getJobName(), req.getNamespace(), image, quote(shell),
                envBlock(req), configMapName(req));
    }

    /** 普通变量 + 凭据引用；没有凭据的中间件（kafka）只出普通变量。 */
    private static String envBlock(AppInitRequestDTO req) {
        Map<String, String> env = new LinkedHashMap<>(req.getEnv() == null ? Map.of() : req.getEnv());
        StringBuilder sb = new StringBuilder("          env:\n");
        boolean any = false;
        for (Map.Entry<String, String> e : env.entrySet()) {
            sb.append("            - name: ").append(e.getKey()).append('\n')
                    .append("              value: ").append(quote(e.getValue())).append('\n');
            any = true;
        }
        if (req.getSecretName() != null && !req.getSecretName().isBlank()) {
            if (req.getUsername() != null && !req.getUsername().isBlank()) {
                sb.append("            - name: USER_NAME\n")
                        .append("              value: ").append(quote(req.getUsername())).append('\n');
                any = true;
            } else if (req.getUsernameKey() != null && !req.getUsernameKey().isBlank()) {
                sb.append("            - name: USER_NAME\n")
                        .append("              valueFrom:\n")
                        .append("                secretKeyRef:\n")
                        .append("                  name: ").append(req.getSecretName()).append('\n')
                        .append("                  key: ").append(req.getUsernameKey()).append('\n');
                any = true;
            }
            String passwordKey = req.getPasswordKey() == null || req.getPasswordKey().isBlank()
                    ? "password" : req.getPasswordKey();
            sb.append("            - name: PASSWORD\n")
                    .append("              valueFrom:\n")
                    .append("                secretKeyRef:\n")
                    .append("                  name: ").append(req.getSecretName()).append('\n')
                    .append("                  key: ").append(passwordKey).append('\n');
            any = true;
        }
        return any ? sb.toString() : "";
    }

    private static String indent(String text, int spaces) {
        String pad = " ".repeat(spaces);
        return (text == null ? "" : text).lines().map(l -> pad + l)
                .reduce((a, b) -> a + "\n" + b).orElse(pad);
    }

    private static String quote(String value) {
        return "\"" + (value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")) + "\"";
    }
}
