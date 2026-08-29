package com.coding.common.models.k8s;

import com.coding.common.models.k8s.dto.ClusterRoleDTO;
import com.coding.common.models.k8s.dto.ConfigMapDTO;
import com.coding.common.models.k8s.dto.DeploymentDTO;
import com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO;
import com.coding.common.models.k8s.dto.PodDTO;
import com.coding.common.models.k8s.dto.RoleBindingDTO;
import com.coding.common.models.k8s.dto.SecretDTO;
import com.coding.common.models.k8s.dto.ServiceAccountDTO;
import com.coding.common.models.k8s.dto.ServiceDTO;
import com.coding.common.models.k8s.dto.ServiceMonitorDTO;
import com.coding.common.models.k8s.dto.WorkloadDTO;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public enum ResourceType {

    DEPLOYMENT(DeploymentDTO.class),
    CLUSTER_ROLE(ClusterRoleDTO.class),
    ROLE_BINDING(RoleBindingDTO.class),
    SERVICE_ACCOUNT(ServiceAccountDTO.class),
    CONFIGMAP(ConfigMapDTO.class),
    SECRET(SecretDTO.class),
    SERVICE(ServiceDTO.class),
    PERSISTENT_VOLUME_CLAIM(PersistentVolumeClaimDTO.class),
    POD(PodDTO.class),
    SERVICE_MONITOR(ServiceMonitorDTO.class),
    WORKLOAD(WorkloadDTO.class)
    ;

    private final Class<?> clazz;

}
