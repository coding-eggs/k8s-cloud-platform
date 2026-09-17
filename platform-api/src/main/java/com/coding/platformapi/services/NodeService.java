package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.NodeDTO;
import com.coding.common.models.k8s.dto.NodePodStatDTO;
import com.coding.platformapi.k8s.K8sResourceClient;
import com.coding.platformapi.k8s.K8sServerGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class NodeService {


    @Autowired
    private K8sResourceClient k8s;
    @Autowired
    private K8sServerGateway gateway;

    public List<NodeDTO> list(NodeDTO body) {
        List<NodeDTO> nodeDTOList = k8s.list(body);
        List<NodePodStatDTO> podStatDTOList = gateway.exchange(HttpMethod.GET, "/resources/nodes/podstats", Map.of("clusterId", body.getClusterId()),
                null, gateway.listResponseType(NodePodStatDTO.class));
        Map<String, NodePodStatDTO> podStatDTOMap =
                podStatDTOList.stream().collect(Collectors.toMap(NodePodStatDTO::getNodeName, e -> e));

        for (NodeDTO nodeDTO : nodeDTOList) {
            fillPodStatus(nodeDTO);
        }
        return nodeDTOList;
    }

    public NodeDTO get(String nodeName, String clusterId) {
        NodeDTO nodeDTO = k8s.get(dto(nodeName, clusterId));
        fillPodStatus(nodeDTO);
        return nodeDTO;
    }

    public void fillPodStatus(NodeDTO nodeDTO) {
        List<NodePodStatDTO> podStatDTOList = gateway.exchange(HttpMethod.GET, "/resources/nodes/podstats",
                Map.of("clusterId", nodeDTO.getClusterId()),
                null, gateway.listResponseType(NodePodStatDTO.class));
        Map<String, NodePodStatDTO> podStatDTOMap =
                podStatDTOList.stream().collect(Collectors.toMap(NodePodStatDTO::getNodeName, e -> e));
        if (podStatDTOMap.containsKey(nodeDTO.getName())) {
            NodePodStatDTO nodePodStatDTO = podStatDTOMap.get(nodeDTO.getName());
            nodeDTO.setPodCount(nodePodStatDTO.getPodCount());
            nodeDTO.setCpuRequestMillicores(nodePodStatDTO.getCpuRequestMillicores());
            nodeDTO.setMemRequestBytes(nodePodStatDTO.getMemRequestBytes());
        }
    }

    public NodeDTO dto(String name, String clusterId) {
        NodeDTO d = new NodeDTO();
        d.setName(name);
        d.setClusterId(clusterId);
        return d;
    }
}
