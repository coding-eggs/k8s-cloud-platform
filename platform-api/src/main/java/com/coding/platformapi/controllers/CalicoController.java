package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.IpamBlockStatDTO;
import com.coding.common.models.k8s.dto.IpamIpDetailDTO;
import com.coding.common.models.k8s.dto.BgpConfigurationDTO;
import com.coding.common.models.k8s.dto.BgpFilterDTO;
import com.coding.common.models.k8s.dto.BgpPeerDTO;
import com.coding.common.models.k8s.dto.CalicoFormOptionDTO;
import com.coding.common.models.k8s.dto.IpoolDTO;
import com.coding.common.models.k8s.dto.IpReservationDTO;
import com.coding.common.models.k8s.dto.PoolIpamSummaryDTO;
import com.coding.common.models.k8s.dto.SecretRefOptionDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.services.CalicoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 网络 Calico（集群级，PLATFORM:admin）：IPPool CRUD + IPAM 派生块视图。
 * <p>面向前端的顶层前缀 {@code /calico/**}；委托 {@link CalicoService}（降级 + TTL 缓存 + 删除守卫），
 * 再经 K8sAdminClient 打 k8s-server {@code /admin/calico/**}。每个端点须有权限行（platform:cluster:manage）或豁免，
 * 否则 PermissionCrossCheckRunner 启动 brick（见 Flyway V2026_10_03_1）。
 */
@Tag(name = "网络-Calico", description = "IPPool CRUD + IPAM 派生块视图（集群级，PLATFORM:admin）")
@RestController
@RequestMapping("/calico")
@RequiredArgsConstructor
public class CalicoController {

    private final CalicoService calico;

    // ==================== IPPool CRUD ====================

    @PostMapping("/ippool/list")
    @Operation(summary = "列出 IPPool（body 传 clusterId/labelSelector）")
    public ResponseData<List<IpoolDTO>> listIppools(@RequestBody IpoolDTO body) {
        return new ResponseData<>(calico.listIppools(body));
    }

    @GetMapping("/ippool/{name}")
    @Operation(summary = "查询 IPPool")
    public ResponseData<IpoolDTO> getIppool(@PathVariable String name, @RequestParam String clusterId) {
        return new ResponseData<>(calico.getIppool(clusterId, name));
    }

    @GetMapping("/ippool/{name}/yaml")
    @Operation(summary = "查询 IPPool YAML（只读）")
    public ResponseData<String> ippoolYaml(@PathVariable String name, @RequestParam String clusterId) {
        return new ResponseData<>(calico.ippoolYaml(clusterId, name));
    }

    @PostMapping("/ippool")
    @Operation(summary = "创建 IPPool")
    public ResponseData<IpoolDTO> createIppool(@RequestParam String clusterId, @RequestBody IpoolDTO body) {
        body.setClusterId(clusterId);
        return new ResponseData<>(calico.createIppool(body));
    }

    @PutMapping("/ippool/{name}")
    @Operation(summary = "更新 IPPool")
    public ResponseData<IpoolDTO> updateIppool(@PathVariable String name, @RequestParam String clusterId,
                                               @RequestBody IpoolDTO body) {
        body.setName(name);
        body.setClusterId(clusterId);
        return new ResponseData<>(calico.updateIppool(body));
    }

    @DeleteMapping("/ippool/{name}")
    @Operation(summary = "删除 IPPool（带守卫：有已分配 IP 拒绝）")
    public ResponseData<Void> deleteIppool(@PathVariable String name, @RequestParam String clusterId) {
        calico.deleteIppool(clusterId, name);
        return new ResponseData<>();
    }

    // ==================== IPReservation CRUD（保留 IP） ====================

    @PostMapping("/ipreservation/list")
    @Operation(summary = "列出 IPReservation（body 传 clusterId/labelSelector）")
    public ResponseData<List<IpReservationDTO>> listIpreervations(@RequestBody IpReservationDTO body) {
        return new ResponseData<>(calico.listIpreervations(body));
    }

    @GetMapping("/ipreservation/{name}")
    @Operation(summary = "查询 IPReservation")
    public ResponseData<IpReservationDTO> getIpreervation(@PathVariable String name, @RequestParam String clusterId) {
        return new ResponseData<>(calico.getIpreervation(clusterId, name));
    }

    @GetMapping("/ipreservation/{name}/yaml")
    @Operation(summary = "查询 IPReservation YAML（只读）")
    public ResponseData<String> ipreservationYaml(@PathVariable String name, @RequestParam String clusterId) {
        return new ResponseData<>(calico.ipreservationYaml(clusterId, name));
    }

    @PostMapping("/ipreservation")
    @Operation(summary = "创建 IPReservation（保留段）")
    public ResponseData<IpReservationDTO> createIpreervation(@RequestParam String clusterId, @RequestBody IpReservationDTO body) {
        body.setClusterId(clusterId);
        return new ResponseData<>(calico.createIpreervation(body));
    }

    @PutMapping("/ipreservation/{name}")
    @Operation(summary = "更新 IPReservation（改保留段）")
    public ResponseData<IpReservationDTO> updateIpreervation(@PathVariable String name, @RequestParam String clusterId,
                                                              @RequestBody IpReservationDTO body) {
        body.setName(name);
        body.setClusterId(clusterId);
        return new ResponseData<>(calico.updateIpreervation(body));
    }

    @DeleteMapping("/ipreservation/{name}")
    @Operation(summary = "删除 IPReservation（释放保留段）")
    public ResponseData<Void> deleteIpreervation(@PathVariable String name, @RequestParam String clusterId) {
        calico.deleteIpreervation(clusterId, name);
        return new ResponseData<>();
    }

    // ==================== IPAM 派生查询（只读） ====================

    @GetMapping("/ipam/summary")
    @Operation(summary = "池 IPAM 汇总")
    public ResponseData<PoolIpamSummaryDTO> ipamSummary(@RequestParam String clusterId, @RequestParam String poolName) {
        return new ResponseData<>(calico.ipamSummary(clusterId, poolName));
    }

    @GetMapping("/ipam/blocks")
    @Operation(summary = "已物化块表（可选按池 / 关键字过滤）")
    public ResponseData<List<IpamBlockStatDTO>> ipamBlocks(@RequestParam String clusterId,
                                                           @RequestParam(required = false) String poolName,
                                                           @RequestParam(required = false) String search) {
        return new ResponseData<>(calico.ipamBlocks(clusterId, poolName, search));
    }

    @GetMapping("/ipam/is-free")
    @Operation(summary = "点查某 IP/块是否空闲")
    public ResponseData<Boolean> ipamIsFree(@RequestParam String clusterId, @RequestParam String cidrOrIp) {
        return new ResponseData<>(calico.ipamIsFree(clusterId, cidrOrIp));
    }

    @GetMapping("/ipam/next-free-blocks")
    @Operation(summary = "下一批空闲块 CIDR（分页）")
    public ResponseData<List<String>> ipamNextFreeBlocks(@RequestParam String clusterId,
                                                         @RequestParam String poolName,
                                                         @RequestParam(defaultValue = "0") int offset,
                                                         @RequestParam(defaultValue = "20") int limit) {
        return new ResponseData<>(calico.ipamNextFreeBlocks(clusterId, poolName, offset, limit));
    }

    @GetMapping("/ipam/block-ips")
    @Operation(summary = "单块 per-IP（未物化合成全 free）")
    public ResponseData<List<IpamIpDetailDTO>> ipamBlockIps(@RequestParam String clusterId, @RequestParam String cidr) {
        return new ResponseData<>(calico.ipamBlockIps(clusterId, cidr));
    }

    // ==================== BGP*（CRUD；Configuration 写仅平台管理员，code platform:bgp:config:manage） ====================

    @PostMapping("/bgpconfiguration/list")
    @Operation(summary = "列出 BGPConfiguration（body 传 clusterId/labelSelector）")
    public ResponseData<List<BgpConfigurationDTO>> listBgpConfigurations(@RequestBody BgpConfigurationDTO body) {
        return new ResponseData<>(calico.listBgpConfigurations(body));
    }

    @GetMapping("/bgpconfiguration/{name}")
    @Operation(summary = "查询 BGPConfiguration")
    public ResponseData<BgpConfigurationDTO> getBgpConfiguration(@PathVariable String name, @RequestParam String clusterId) {
        return new ResponseData<>(calico.getBgpConfiguration(clusterId, name));
    }

    @GetMapping("/bgpconfiguration/{name}/yaml")
    @Operation(summary = "查询 BGPConfiguration YAML（只读）")
    public ResponseData<String> bgpConfigurationYaml(@PathVariable String name, @RequestParam String clusterId) {
        return new ResponseData<>(calico.bgpConfigurationYaml(clusterId, name));
    }

    @PostMapping("/bgpconfiguration")
    @Operation(summary = "创建 BGPConfiguration（仅平台管理员）")
    public ResponseData<BgpConfigurationDTO> createBgpConfiguration(@RequestParam String clusterId,
                                                                    @RequestBody BgpConfigurationDTO body) {
        return new ResponseData<>(calico.createBgpConfiguration(body));
    }

    @PutMapping("/bgpconfiguration/{name}")
    @Operation(summary = "更新 BGPConfiguration（仅平台管理员）")
    public ResponseData<BgpConfigurationDTO> updateBgpConfiguration(@PathVariable String name, @RequestParam String clusterId,
                                                                    @RequestBody BgpConfigurationDTO body) {
        return new ResponseData<>(calico.updateBgpConfiguration(body));
    }

    @DeleteMapping("/bgpconfiguration/{name}")
    @Operation(summary = "删除 BGPConfiguration（仅平台管理员）")
    public ResponseData<Void> deleteBgpConfiguration(@PathVariable String name, @RequestParam String clusterId) {
        calico.deleteBgpConfiguration(clusterId, name);
        return new ResponseData<>();
    }

    @PostMapping("/bgppeer/list")
    @Operation(summary = "列出 BGPPeer（body 传 clusterId/labelSelector）")
    public ResponseData<List<BgpPeerDTO>> listBgpPeers(@RequestBody BgpPeerDTO body) {
        return new ResponseData<>(calico.listBgpPeers(body));
    }

    @GetMapping("/bgppeer/{name}")
    @Operation(summary = "查询 BGPPeer")
    public ResponseData<BgpPeerDTO> getBgpPeer(@PathVariable String name, @RequestParam String clusterId) {
        return new ResponseData<>(calico.getBgpPeer(clusterId, name));
    }

    @GetMapping("/bgppeer/{name}/yaml")
    @Operation(summary = "查询 BGPPeer YAML（只读）")
    public ResponseData<String> bgpPeerYaml(@PathVariable String name, @RequestParam String clusterId) {
        return new ResponseData<>(calico.bgpPeerYaml(clusterId, name));
    }

    @PostMapping("/bgppeer")
    @Operation(summary = "创建 BGPPeer")
    public ResponseData<BgpPeerDTO> createBgpPeer(@RequestParam String clusterId, @RequestBody BgpPeerDTO body) {
        return new ResponseData<>(calico.createBgpPeer(body));
    }

    @PutMapping("/bgppeer/{name}")
    @Operation(summary = "更新 BGPPeer")
    public ResponseData<BgpPeerDTO> updateBgpPeer(@PathVariable String name, @RequestParam String clusterId,
                                                  @RequestBody BgpPeerDTO body) {
        return new ResponseData<>(calico.updateBgpPeer(body));
    }

    @DeleteMapping("/bgppeer/{name}")
    @Operation(summary = "删除 BGPPeer")
    public ResponseData<Void> deleteBgpPeer(@PathVariable String name, @RequestParam String clusterId) {
        calico.deleteBgpPeer(clusterId, name);
        return new ResponseData<>();
    }

    @PostMapping("/bgpfilter/list")
    @Operation(summary = "列出 BGPFilter（body 传 clusterId/labelSelector）")
    public ResponseData<List<BgpFilterDTO>> listBgpFilters(@RequestBody BgpFilterDTO body) {
        return new ResponseData<>(calico.listBgpFilters(body));
    }

    @GetMapping("/bgpfilter/{name}")
    @Operation(summary = "查询 BGPFilter")
    public ResponseData<BgpFilterDTO> getBgpFilter(@PathVariable String name, @RequestParam String clusterId) {
        return new ResponseData<>(calico.getBgpFilter(clusterId, name));
    }

    @GetMapping("/bgpfilter/{name}/yaml")
    @Operation(summary = "查询 BGPFilter YAML（只读）")
    public ResponseData<String> bgpFilterYaml(@PathVariable String name, @RequestParam String clusterId) {
        return new ResponseData<>(calico.bgpFilterYaml(clusterId, name));
    }

    @PostMapping("/bgpfilter")
    @Operation(summary = "创建 BGPFilter")
    public ResponseData<BgpFilterDTO> createBgpFilter(@RequestParam String clusterId, @RequestBody BgpFilterDTO body) {
        return new ResponseData<>(calico.createBgpFilter(body));
    }

    @PutMapping("/bgpfilter/{name}")
    @Operation(summary = "更新 BGPFilter")
    public ResponseData<BgpFilterDTO> updateBgpFilter(@PathVariable String name, @RequestParam String clusterId,
                                                      @RequestBody BgpFilterDTO body) {
        return new ResponseData<>(calico.updateBgpFilter(body));
    }

    @DeleteMapping("/bgpfilter/{name}")
    @Operation(summary = "删除 BGPFilter")
    public ResponseData<Void> deleteBgpFilter(@PathVariable String name, @RequestParam String clusterId) {
        calico.deleteBgpFilter(clusterId, name);
        return new ResponseData<>();
    }

    @PostMapping("/form-options")
    @Operation(summary = "BGP 编辑器下拉候选（namespaces / workloads；只读）")
    public ResponseData<CalicoFormOptionDTO> formOptions(@RequestParam String clusterId) {
        return new ResponseData<>(calico.formOptions(clusterId));
    }

    @GetMapping("/form-options/secrets")
    @Operation(summary = "某命名空间下的 Secret 引用候选（name + data keys；级联第二级，只读）")
    public ResponseData<List<SecretRefOptionDTO>> secretOptions(@RequestParam String clusterId,
                                                                @RequestParam String namespace) {
        return new ResponseData<>(calico.secretOptions(clusterId, namespace));
    }

}
