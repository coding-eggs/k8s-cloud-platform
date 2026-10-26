package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecWatch;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 在 Pod 里跑一条命令（用户管理里只有 MySQL 需要：它的 operator 没有声明式用户字段）。
 *
 * <p><b>口令走 stdin，不上命令行</b>：命令固定写成
 * {@code sh -c 'read -r P; export MYSQL_PWD="$P"; exec mysql -uroot -N -B -e "$1"' -- <sql>}，
 * 明文只出现在容器的环境变量里（`ps` 看不到）。
 *
 * <p>输出上限 8KB、超时 15s；非 0 退出码抛异常（消息里带 stderr 前 300 字，**不含口令**）。
 */
final class PodExec {

    private static final long TIMEOUT_SECONDS = 15;
    private static final int MAX_MESSAGE = 300;

    private PodExec() {
    }

    /**
     * @param stdin 需要喂给命令标准输入的内容（可为 null）
     * @return 标准输出
     */
    static String run(KubernetesClient client, String namespace, String pod, String container,
                      String stdin, String... command) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (ExecWatch watch = client.pods().inNamespace(namespace).withName(pod)
                .inContainer(container)
                .redirectingInput()
                .writingOutput(out)
                .writingError(err)
                .exec(command)) {
            if (stdin != null) {
                watch.getInput().write(stdin.getBytes(StandardCharsets.UTF_8));
                watch.getInput().flush();
            }
            Integer code = watch.exitCode().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (code == null || code != 0) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "在 " + namespace + "/" + pod + " 执行命令失败（exit " + code + "）：" + brief(err));
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (CloudPlatformException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "在 " + namespace + "/" + pod + " 执行命令失败：" + e.getMessage());
        }
    }

    private static String brief(ByteArrayOutputStream err) {
        String text = err.toString(StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
        return text.length() <= MAX_MESSAGE ? text : text.substring(0, MAX_MESSAGE) + "…";
    }
}
