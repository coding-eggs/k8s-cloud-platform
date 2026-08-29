package com.coding.k8score.converter.impl.rbac;

import com.coding.common.models.k8s.dto.ClusterRoleDTO;
import com.coding.common.models.k8s.dto.PolicyRuleDTO;
import com.coding.k8score.converter.CommonConverter;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.rbac.ClusterRole;
import io.fabric8.kubernetes.api.model.rbac.PolicyRule;
import io.fabric8.kubernetes.api.model.rbac.PolicyRuleBuilder;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * rbac/v1 版本的 ClusterRole DTO 转换器
 */
public class RbacV1ClusterRoleConverter implements CommonConverter<ClusterRole, ClusterRoleDTO> {

    @Override
    public ClusterRole convert(ClusterRoleDTO dto) {
        ClusterRole clusterRole = new ClusterRole();

        clusterRole.setMetadata(new ObjectMetaBuilder()
                .withName(dto.getName())
                .withLabels(dto.getLabels())
                .build());

        if (dto.getRules() != null && !dto.getRules().isEmpty()) {
            List<PolicyRule> rules = dto.getRules().stream().map(r ->
                    new PolicyRuleBuilder()
                            .withApiGroups(r.getApiGroups() != null ? r.getApiGroups() : Collections.emptyList())
                            .withResources(r.getResources() != null ? r.getResources() : Collections.emptyList())
                            .withVerbs(r.getVerbs() != null ? r.getVerbs() : Collections.emptyList())
                            .withResourceNames(r.getResourceNames() != null ? r.getResourceNames() : Collections.emptyList())
                            .withNonResourceURLs(r.getNonResourceURLs() != null ? r.getNonResourceURLs() : Collections.emptyList())
                            .build()
            ).toList();
            clusterRole.setRules(rules);
        }

        return clusterRole;
    }

    @Override
    public ClusterRoleDTO revert(ClusterRole clusterRole) {
        if (clusterRole == null) return null;

        ClusterRoleDTO dto = new ClusterRoleDTO();

        var metadata = clusterRole.getMetadata();
        if (metadata != null) {
            dto.setName(metadata.getName());
            dto.setLabels(metadata.getLabels() != null ? Map.copyOf(metadata.getLabels()) : Map.of());
        }

        List<PolicyRule> rules = clusterRole.getRules();
        if (rules != null && !rules.isEmpty()) {
            dto.setRules(rules.stream().map(this::toPolicyRuleDTO).toList());
        } else {
            dto.setRules(Collections.emptyList());
        }

        return dto;
    }

    private PolicyRuleDTO toPolicyRuleDTO(PolicyRule rule) {
        PolicyRuleDTO dto = new PolicyRuleDTO();
        dto.setApiGroups(rule.getApiGroups() != null ? List.copyOf(rule.getApiGroups()) : Collections.emptyList());
        dto.setResources(rule.getResources() != null ? List.copyOf(rule.getResources()) : Collections.emptyList());
        dto.setVerbs(rule.getVerbs() != null ? List.copyOf(rule.getVerbs()) : Collections.emptyList());
        dto.setResourceNames(rule.getResourceNames() != null ? List.copyOf(rule.getResourceNames()) : Collections.emptyList());
        dto.setNonResourceURLs(rule.getNonResourceURLs() != null ? List.copyOf(rule.getNonResourceURLs()) : Collections.emptyList());
        return dto;
    }

}