package com.coding.k8score.converter.impl.rbac;

import com.coding.common.models.k8s.dto.RoleBindingDTO;
import com.coding.common.models.k8s.dto.RoleRefDTO;
import com.coding.common.models.k8s.dto.SubjectDTO;
import com.coding.k8score.converter.CommonConverter;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleRef;
import io.fabric8.kubernetes.api.model.rbac.RoleRefBuilder;
import io.fabric8.kubernetes.api.model.rbac.Subject;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * rbac/v1 版本的 RoleBinding DTO 转换器
 */
public class RbacV1RoleBindingConverter implements CommonConverter<RoleBinding, RoleBindingDTO> {

    @Override
    public RoleBinding convert(RoleBindingDTO dto) {
        RoleBinding roleBinding = new RoleBinding();

        String ns = dto.getNamespace() != null ? dto.getNamespace() : "";
        roleBinding.setMetadata(new ObjectMetaBuilder()
                .withName(dto.getName())
                .withNamespace(ns)
                .withLabels(dto.getLabels())
                .build());

        RoleRefDTO refDto = dto.getRoleRef();
        if (refDto != null) {
            roleBinding.setRoleRef(new RoleRefBuilder()
                    .withApiGroup(refDto.getApiGroup())
                    .withKind(refDto.getKind())
                    .withName(refDto.getName())
                    .build());
        }

        if (dto.getSubjects() != null && !dto.getSubjects().isEmpty()) {
            List<Subject> subjects = dto.getSubjects().stream().map(s ->
                    new io.fabric8.kubernetes.api.model.rbac.SubjectBuilder()
                            .withApiGroup(s.getApiGroup())
                            .withKind(s.getKind())
                            .withName(s.getName())
                            .withNamespace(s.getNamespace())
                            .build()
            ).toList();
            roleBinding.setSubjects(subjects);
        }

        return roleBinding;
    }

    @Override
    public RoleBindingDTO revert(RoleBinding roleBinding) {
        if (roleBinding == null) return null;

        RoleBindingDTO dto = new RoleBindingDTO();

        var metadata = roleBinding.getMetadata();
        if (metadata != null) {
            dto.setName(metadata.getName());
            dto.setNamespace(metadata.getNamespace());
            dto.setLabels(metadata.getLabels() != null ? Map.copyOf(metadata.getLabels()) : Map.of());
        }

        RoleRef roleRef = roleBinding.getRoleRef();
        if (roleRef != null) {
            RoleRefDTO refDto = new RoleRefDTO();
            refDto.setApiGroup(roleRef.getApiGroup());
            refDto.setKind(roleRef.getKind());
            refDto.setName(roleRef.getName());
            dto.setRoleRef(refDto);
        }

        List<Subject> subjects = roleBinding.getSubjects();
        if (subjects != null && !subjects.isEmpty()) {
            dto.setSubjects(subjects.stream().map(this::toSubjectDTO).toList());
        } else {
            dto.setSubjects(Collections.emptyList());
        }

        return dto;
    }

    private SubjectDTO toSubjectDTO(Subject s) {
        SubjectDTO dto = new SubjectDTO();
        dto.setApiGroup(s.getApiGroup());
        dto.setKind(s.getKind());
        dto.setName(s.getName());
        dto.setNamespace(s.getNamespace());
        return dto;
    }

}