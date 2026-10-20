package com.coding.k8sserver.pipeline;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.pipeline.PipelineRunSnapshotDTO;
import com.coding.common.utils.ULIDGenerator;
import com.coding.data.mapper.system.PlatformPipelineRunLogMapper;
import com.coding.data.mapper.system.PlatformPipelineRunMapper;
import com.coding.data.models.system.PlatformPipelineRun;
import com.coding.data.models.system.PlatformPipelineRunLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 归档的**落库那一半**：把快照写进平台库。
 *
 * <h2>⚠️ 它为什么在 k8s-server 而不是 platform-api</h2>
 * 因为抓快照的就是 k8s-server（watch 是它开的，收到终结事件时集群对象、TaskRun、Pod 都还在它手上），
 * 而它**本来就有 platform-data**（{@code K8sClusterMapper} 早就在用）、{@code @MapperScan}
 * 与事务也都已启用。于是「抓完直接写」不需要任何跨进程调用 ——
 * 也就**不需要**那条免认证的入站端点、那个共享密钥、以及配套的 ack / 待补 / 重投语义。
 *
 * <p>代价如实记着：运行表从此有**两个写入方**（这里是归档，platform-api 是触发与保留清理），
 * 且「k8s-server 零业务逻辑」在这条链上让了步。换来的是**入站攻击面归零**。
 *
 * <p>本服务只管两件事：**幂等**（同一条运行重投多少次都只写一遍）与
 * **事务**（日志行与运行状态要么一起进，要么都不进）。
 *
 * <h2>两个入口，别混用</h2>
 * <ul>
 *   <li>{@link #persist} —— **结案**：写日志 <b>+</b> 写状态/时间/{@code steps_json}/YAML。
 *       只在运行**真的终结**时用（watch 的终止事件、平台判超时）。</li>
 *   <li>{@link #persistLogs} —— **只写日志**：取消前那次抢抓用（那一刻对象还在跑）。
 *       运行行原样不动，结案仍归后续的终止事件。</li>
 * </ul>
 */
@Slf4j
@Service
public class PipelineArchiveService {

    private final PlatformPipelineRunMapper runMapper;
    private final PlatformPipelineRunLogMapper logMapper;

    public PipelineArchiveService(PlatformPipelineRunMapper runMapper,
                                  PlatformPipelineRunLogMapper logMapper) {
        this.runMapper = runMapper;
        this.logMapper = logMapper;
    }

    /**
     * **只落日志，不结案** —— 取消路径专用（spec §4.2）。
     *
     * <p>为什么不能用 {@link #persist}：取消的那一刻对象**还在跑**，{@code snapshot.status()}
     * 就是 {@code RUNNING}，而 persist 的「尚未终结不能归档」守卫（对归档路径是对的）
     * 会每次都把这份抢抓的日志整条扔掉 —— 随后 Tekton 取消时自己删掉 Pod，
     * 那份日志就再也拿不到了。
     *
     * <p>为什么也不能顺手结案：这次运行**还没终结**。状态、时间、{@code steps_json} 与 YAML
     * 都归后续的终止事件（走 {@link #persist}）写 —— 这里只把日志先存下来，
     * 运行行保持 {@code RUNNING}，历史里它就是「跑着 / 正在被取消」。
     *
     * <p>与 {@link #persist} 一样是**幂等**的：重投 / 反复取消都只留一行
     * （唯一索引 (run_id, node_name) + ON DUPLICATE KEY）。
     */
    @Transactional
    public void persistLogs(PipelineRunSnapshotDTO snapshot) {
        PlatformPipelineRun row = runMapper.selectByRunName(snapshot.runName());
        if (row == null) {
            // 同 persist：不是平台发起的运行（或保留清理已经把它删了）—— 没有可挂的行
            log.warn("抢抓日志时库里没有这条运行（跳过落库）：{}", snapshot.runName());
            return;
        }
        if (!"RUNNING".equals(row.getStatus())) {
            // 已经结案了就别再把抢抓那份盖上去 —— ON DUPLICATE KEY 是无条件覆写，
            // 而抢抓到的多半是**没跑完**的那半截，比结案时那份完整日志短
            log.debug("这条运行已经归档过了，跳过抢抓落库：{}", snapshot.runName());
            return;
        }
        writeLogs(row.getId(), snapshot.steps());
    }

    /**
     * 落库。**幂等**：重投时只写一次。
     *
     * <p>⚠️ 已归档时**返回正常**（而不是抛）—— 调用方据此才去删对象。
     * 这里如果抛，就变成「库早就写好了，但对象永远删不掉」。
     */
    @Transactional
    public void persist(PipelineRunSnapshotDTO snapshot) {
        PlatformPipelineRun row = runMapper.selectByRunName(snapshot.runName());
        if (row == null) {
            // 不是平台发起的运行（或保留清理已经把它删了）—— 落不了库，但对象该由调用方删
            log.warn("归档时库里没有这条运行（跳过落库，对象照删）：{}", snapshot.runName());
            return;
        }
        if (!"RUNNING".equals(row.getStatus())) {
            log.debug("这条运行已经归档过了，跳过落库：{}", snapshot.runName());
            return;
        }
        if ("RUNNING".equals(snapshot.status())) {
            // 防御：只在终结（或平台判超时）时才抓快照。真到了这里说明判断有误 ——
            // **不写也不删**，留着对象与 Pod（那份日志还在），比删掉再想找回要好
            throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                    "该运行尚未终结，不能归档：" + snapshot.runName());
        }

        List<PipelineRunSnapshotDTO.StepLog> steps =
                snapshot.steps() == null ? List.of() : snapshot.steps();
        writeLogs(row.getId(), steps);

        row.setStatus(snapshot.status());
        // ⚠️ 这里现在是**失败节点的原话**（可能是个长句子），而列是 varchar(255)：
        // 必须截断 —— 超长在 MySQL 严格模式下是 SQLException，而「落库失败 = 对象不删」，
        // 于是台账会一直重试、集群里那个对象永远留着
        row.setReason(clip(snapshot.reason()));
        row.setStartTime(snapshot.startTime());
        row.setCompletionTime(snapshot.completionTime());
        row.setStepsJson(stepsJson(steps));
        // YAML 是**归档这一刻**才拿得到的东西（对象马上就被删了），所以它跟着这次回写一起进去
        row.setRunYaml(snapshot.runYaml());
        runMapper.update(row);
    }

    /**
     * 把一条**集群对象已经不在**的运行结案。
     *
     * <p>⭐ 这个入口是 2026-10-20 补的。之前所有路径都以「集群里还能看到这个 PipelineRun」为前提：
     * watch 只在对象还在时发事件、启动扫描只列集群里还在的对象、取消对不存在的对象是 no-op——
     * 于是「运行中升级 Tekton，对象被带走」的运行会**永远停在 RUNNING**：删除按钮不出现，
     * 后端也拒删。对象没了，至少库里那行要有人结案。
     *
     * <p>状态取 {@code FAILED} 而**不新增一个状态值**：新值要在前端三处状态映射里各加一份，
     * 收益为零；而且它确实不是成功，FAILED 的语义（未成功完成、不是用户取消）是准的。
     *
     * <p>幂等：mapper 的语句带着 {@code and status = 'RUNNING'}，重复调用只生效一次，
     * 已经归档过的行一个字节都不改（不会拿空值盖掉 {@code steps_json} / {@code run_yaml}）。
     *
     * @return true = 这次真的结了案（之前是 RUNNING）；false = 已经不归它管了
     */
    @Transactional
    public boolean markLost(String runName) {
        int changed = runMapper.markLost(runName, LOST_REASON, nowStamp());
        if (changed > 0) {
            log.warn("运行对象已不在集群中，按失败结案：{}", runName);
        }
        return changed > 0;
    }

    /** 结案理由。<b>不要写成节点失败的原话</b> —— 这条运行根本没有节点状态可抓。 */
    static final String LOST_REASON =
            "运行对象已不在集群中（可能由 Tekton 升级/重启或他人删除），平台未能归档";

    /**
     * {@code completion_time} 是 varchar，且保留清理拿它做**字符串比较**。
     * 固定成「秒级、Z 结尾」正是 Tekton {@code metav1.Time} 的形状 —— 带小数秒会让同一秒内的比较失真。
     */
    private static String nowStamp() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /**
     * 日志行的那一段 —— {@link #persist} 与 {@link #persistLogs} 共用。
     *
     * <p>「没有日志」与「日志是空的」不是一回事 —— 空日志不写行。
     * 唯一索引 (run_id, node_name) + ON DUPLICATE KEY 保证同一节点只留一行。
     */
    private void writeLogs(String runId, List<PipelineRunSnapshotDTO.StepLog> steps) {
        if (steps == null) {
            return;
        }
        for (PipelineRunSnapshotDTO.StepLog step : steps) {
            if (step.log() == null || step.log().isEmpty()) {
                continue;
            }
            PlatformPipelineRunLog line = new PlatformPipelineRunLog();
            line.setId(ULIDGenerator.generateULID());
            line.setRunId(runId);
            line.setNodeName(step.nodeName());
            line.setContent(step.log());
            line.setTruncated(step.truncated() ? 1 : 0);
            line.setSizeBytes(step.sizeBytes());
            logMapper.insert(line);           // 唯一索引 (run_id, node_name) + ON DUPLICATE KEY
        }
    }

    /** reason 列宽（varchar(1024)，见 V2026_10_15_4）。改这里必须同时改那个迁移。 */
    private static final int REASON_MAX = 1024;

    /** 超长就截断并留个省略号 —— 宁可少几个字，也不要因为落库失败把对象永远留在集群里。 */
    private static String clip(String s) {
        if (s == null || s.length() <= REASON_MAX) {
            return s;
        }
        return s.substring(0, REASON_MAX - 1) + "…";
    }

    private static String stepsJson(List<PipelineRunSnapshotDTO.StepLog> steps) {        return steps.stream()
                .map(s -> s.nodeName() + "=" + s.status())
                .reduce((a, b) -> a + "," + b)
                .orElse(null);
    }
}
