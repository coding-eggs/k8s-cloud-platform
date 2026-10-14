package com.coding.k8score.operations.pipelinerun;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.PipelineRunDTO;
import com.coding.k8score.converter.impl.pipeline.PipelineRunConverter;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.pipeline.PipelineCanvasOperations;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.tekton.v1.PipelineRun;
import io.fabric8.tekton.v1.PipelineRunSpec;
import io.fabric8.tekton.v1.StepState;
import io.fabric8.tekton.v1.TaskRun;
import io.fabric8.tekton.v1.TaskRunStatus;
import com.coding.common.models.pipeline.PipelineStepRefDTO;
import io.fabric8.tekton.v1.PipelineSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tekton {@code PipelineRun} 操作（{@code tekton.dev/v1}，命名空间级、租户域）。
 *
 * <h2>⚠️ 本类最重要的不变量：只碰平台自己创建的 run</h2>
 * 集群里可能有**别人手工建的** {@code PipelineRun}（运维调试、别的 CI 系统）。
 * 平台会**删掉**归档完的 run —— 不带过滤的话就是删别人的东西，这是本批最容易造成事故的一处。
 * 所以 {@link #MANAGED_SELECTOR} 出现在每一个读与每一个写上，并且
 * {@link #assertManaged} 是最后一道闸（spec §4.1.1）。
 *
 * <p>⚠️ 读单对象的三条路（get / delete / yaml / cancel）走的是「先 get 再判标签」而不是
 * 带 selector 的 get —— K8s 的单对象 get 不支持 labelSelector。判标签这一步不能省：
 * 少了它，「看不见别人的 run」就只是 list 的假象，直接按名字打过来照样能删。
 */
@Slf4j
@RequiredArgsConstructor
public class PipelineRunOperations implements NamespacedOperations<PipelineRunDTO> {

    public static final String MANAGED_LABEL = "app.kubernetes.io/managed-by";
    public static final String MANAGED_VALUE = "platform-system";
    public static final String MANAGED_SELECTOR = MANAGED_LABEL + "=" + MANAGED_VALUE;

    private final KubernetesClient client;
    private final PipelineRunConverter converter;
    /** 画布渲染 —— platform-api 不碰 fabric8，所以创建时收到的是画布、渲染在这里做（spec §7 的改定块）。 */
    private final PipelineCanvasOperations canvasOps;

    @Override
    public String apiVersion() {
        return "tekton.dev/v1";
    }

    /** 调用方的标签 + 平台标签。**平台标签最后写** —— 调用方覆盖不了它。 */
    public static Map<String, String> managedLabels(Map<String, String> extra) {
        Map<String, String> out = new HashMap<>(extra == null ? Map.of() : extra);
        out.put(MANAGED_LABEL, MANAGED_VALUE);
        return out;
    }

    /** 不是平台创建的一律拒 —— 读、删、取消都必须先过这里。 */
    public static void assertManaged(Map<String, String> labels, String name) {
        if (labels == null || !MANAGED_VALUE.equals(labels.get(MANAGED_LABEL))) {
            throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                    "该 PipelineRun 不是平台创建的，平台不会碰它：" + name);
        }
    }

    /** 平台标签与调用方给的 selector 用逗号连接（K8s 的 AND 语义）。 */
    private static String withManaged(String labelSelector) {
        return StringUtils.hasText(labelSelector)
                ? MANAGED_SELECTOR + "," + labelSelector
                : MANAGED_SELECTOR;
    }

    @Override
    public List<PipelineRunDTO> list(String namespace, String labelSelector, String fieldSelector) {
        String ns = StringUtils.hasText(namespace) ? namespace : "";
        ListOptions options = new ListOptions();
        options.setLabelSelector(withManaged(labelSelector));
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        return client.resources(PipelineRun.class).inNamespace(ns).list(options).getItems()
                .stream().map(converter::toDto).toList();
    }

    /**
     * 集群级 list：**给启动扫描用** —— 找「带平台标签且还在 running」的 run。
     * 未实现会在接口默认实现里抛 {@code OPERATION_NOT_SUPPORTED}，本类覆写它。
     */
    @Override
    public List<PipelineRunDTO> listAll(String labelSelector, String fieldSelector) {
        ListOptions options = new ListOptions();
        options.setLabelSelector(withManaged(labelSelector));
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        return client.resources(PipelineRun.class).inAnyNamespace().list(options).getItems()
                .stream().map(converter::toDto).toList();
    }

    @Override
    public PipelineRunDTO get(String namespace, String name) {
        PipelineRun pr = fetch(namespace, name);
        if (pr == null) {
            return null;
        }
        assertManaged(pr.getMetadata().getLabels(), name);
        return converter.toDto(pr);
    }

    @Override
    public void delete(String namespace, String name) {
        // 先 get 再删 —— 必须确认它带平台标签。级联：删 PipelineRun 会带走 TaskRun 与 **Pod**
        PipelineRun pr = fetch(namespace, name);
        if (pr == null) {
            return;                                   // 幂等：已经没了就是成功
        }
        assertManaged(pr.getMetadata().getLabels(), name);
        client.resources(PipelineRun.class).inNamespace(namespace).withName(name).delete();
    }

    @Override
    public String yaml(String namespace, String name) {
        PipelineRun pr = fetch(namespace, name);
        if (pr == null) {
            return null;
        }
        assertManaged(pr.getMetadata().getLabels(), name);
        return Serialization.asYaml(pr);
    }

    /**
     * 创建。**接收画布、在这里渲染**成内联的 {@code pipelineSpec}（集群里不留 {@code Pipeline} 对象，
     * spec §3.2）并**打上平台标签**（{@link #managedLabels}）。
     *
     * <p>⚠️ 渲染放在这一步而不是 platform-api：那边不依赖 k8s-core/fabric8（spec §7 的改定块）。
     *
     * @param dto 用 {@code pipelineCanvasJson} 传平台自己的画布 JSON
     */
    @Override
    public PipelineRunDTO create(PipelineRunDTO dto) {
        if (!StringUtils.hasText(dto.getPipelineCanvasJson())) {
            throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                    "创建流水线运行必须带画布");
        }
        PipelineSpec spec = canvasOps.render(dto.getPipelineCanvasJson());

        PipelineRun pr = new PipelineRun();
        // ⚠️ tekton-model 不初始化 metadata —— 不先 setMetadata 就 NPE
        pr.setMetadata(new ObjectMeta());
        pr.getMetadata().setName(dto.getName());
        pr.getMetadata().setNamespace(dto.getNamespace());
        pr.getMetadata().setLabels(managedLabels(dto.getLabels()));

        PipelineRunSpec runSpec = new PipelineRunSpec();
        runSpec.setPipelineSpec(spec);
        // ⚠️ 有意**不设** spec.timeouts —— 超时由平台判定（spec §4.2）。让 Tekton 自己超时
        // 会删掉 Pod，那批运行的日志必然丢失，与「日志进库」直接冲突。
        pr.setSpec(runSpec);

        PipelineRun created = client.resources(PipelineRun.class).inNamespace(dto.getNamespace())
                .resource(pr).create();
        return converter.toDto(created);
    }

    @Override
    public PipelineRunDTO update(PipelineRunDTO resource) {
        // 流水线运行**不可编辑**（改了就不是同一次运行了）。要改就取消重跑。
        throw new CloudPlatformException(EnumResponseType.OPERATION_NOT_SUPPORTED,
                "流水线运行不支持编辑 —— 取消后重新触发一次");
    }

    /**
     * 取消：**patch {@code spec.status}**，不是删对象 —— 删了历史就没了（spec §4.2）。
     *
     * <p>⚠️ 具体取值（v1 里是 {@code "Cancelled"} 还是要用别的）**见 spec §11 待实测** ——
     * 全量测试绿不了这一条，只能在真集群上验。
     */
    @Override
    public void cancel(String namespace, String name) {
        PipelineRun pr = fetch(namespace, name);
        if (pr == null) {
            return;
        }
        assertManaged(pr.getMetadata().getLabels(), name);
        client.resources(PipelineRun.class).inNamespace(namespace).withName(name)
                .edit(p -> {
                    p.getSpec().setStatus("Cancelled");
                    return p;
                });
    }

    /** 一次运行的每个 TaskRun 名（归档时要按它去取 Pod 与日志）。 */
    public List<String> childTaskRunNames(String namespace, String runName) {
        PipelineRun pr = fetch(namespace, runName);
        if (pr == null || pr.getStatus() == null || pr.getStatus().getChildReferences() == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        pr.getStatus().getChildReferences().forEach(ref -> {
            if (ref.getName() != null) {
                out.add(ref.getName());
            }
        });
        return out;
    }

    /**
     * 一条运行里每个节点在集群上的落点（Pod + 容器）。
     *
     * <p>⚠️ <b>「run → TaskRun → Pod → 容器」这套解析放在这里</b>，不放在 platform-api：
     * 那是 Tekton 的结构知识，而 platform-api 不依赖 k8s-core（spec §7 的改定块）。
     * 回的是**已经解析好的落点**，调用方拿着 podName + containers 去走既有的 Pod 日志通道。
     */
    public List<PipelineStepRefDTO> steps(String namespace, String runName) {
        PipelineRun pr = fetch(namespace, runName);
        if (pr == null) {
            return List.of();
        }
        assertManaged(pr.getMetadata().getLabels(), runName);

        List<PipelineStepRefDTO> out = new ArrayList<>();
        for (String taskRunName : childTaskRunNames(namespace, runName)) {
            TaskRun tr = client.resources(TaskRun.class).inNamespace(namespace)
                    .withName(taskRunName).get();
            if (tr != null) {
                out.add(toStepRef(taskRunName, tr));
            }
        }
        return out;
    }

    /**
     * TaskRun → 落点。**纯函数**（不碰 client），所以可单测。
     *
     * <p>节点名取 TaskRun 名：平台的 PipelineTask 名就是画布节点 id，Tekton 给子对象起名时
     * 用的是那个名字（可能带后缀），所以 {@code childReferences} 里的名字才是准的 ——
     * 这里直接用它。
     */
    static PipelineStepRefDTO toStepRef(String nodeName, TaskRun tr) {
        TaskRunStatus st = tr.getStatus();
        String status = PipelineRunConverter.mapConditions(st == null ? null : st.getConditions());
        String podName = st == null ? null : st.getPodName();
        List<String> containers = (st == null || st.getSteps() == null) ? List.of()
                : st.getSteps().stream()
                        .map(StepState::getContainer)
                        .filter(c -> c != null && !c.isBlank())
                        .toList();
        return new PipelineStepRefDTO(nodeName, status, podName, containers);
    }

    private PipelineRun fetch(String namespace, String name) {
        return client.resources(PipelineRun.class).inNamespace(namespace).withName(name).get();
    }
}
