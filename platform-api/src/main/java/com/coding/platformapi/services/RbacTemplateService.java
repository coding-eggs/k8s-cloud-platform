package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.PolicyRuleDTO;
import com.coding.common.utils.K8sNaming;
import com.coding.common.utils.ULIDGenerator;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.k8s.PlatformRbacTemplateMapper;
import com.coding.data.models.k8s.PlatformRbacTemplate;
import com.coding.platformapi.models.RbacTemplateVO;
import com.coding.platformapi.models.RulesPayload;
import com.coding.platformapi.models.TemplateCreateRequest;
import com.coding.platformapi.models.TemplateKeyRequest;
import com.coding.platformapi.models.TemplateUpdateRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Date;
import java.util.List;

/**
 * RBAC 模板管理：模板 = ClusterRole 规则集（tn-tpl-<name>），保存后同步各启用集群
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RbacTemplateService {

    private final PlatformRbacTemplateMapper templateMapper;
    private final PlatformTenantNamespaceMapper allocationMapper;
    private final K8sProvisioningService provisioning;
    private final JsonMapper jsonMapper;

    public RbacTemplateVO create(TemplateCreateRequest req) {
        K8sNaming.validateRawName(req.getName(), K8sNaming.TEMPLATE_ROLE_PREFIX, "name");
        if (templateMapper.selectByName(req.getName()) != null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
        }
        validateRules(req.getRules());

        Date now = new Date(System.currentTimeMillis());
        PlatformRbacTemplate template = new PlatformRbacTemplate();
        template.setId(ULIDGenerator.generateULID());
        template.setName(req.getName());
        template.setDescription(req.getDescription());
        template.setRules(jsonMapper.writeValueAsString(new RulesPayload(req.getRules())));
        template.setBuiltIn(0);
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        templateMapper.insert(template);

        provisioning.syncTemplateToAllEnabledClusters(template);
        return toVO(template);
    }

    public List<RbacTemplateVO> list() {
        return templateMapper.listAll().stream().map(this::toVO).toList();
    }

    public RbacTemplateVO get(TemplateKeyRequest req) {
        return toVO(require(req.getId()));
    }

    /**
     * 更新描述/规则（内置模板不可改；名称创建后不可改，避免各集群改名）
     */
    public RbacTemplateVO update(TemplateUpdateRequest req) {
        PlatformRbacTemplate existing = require(req.getId());
        if (existing.getBuiltIn() == 1) {
            throw new CloudPlatformException(EnumResponseType.RBAC_TEMPLATE_BUILTIN_PROTECTED);
        }
        if (req.getRules() != null) {
            validateRules(req.getRules());
        }

        PlatformRbacTemplate upd = new PlatformRbacTemplate();
        upd.setId(existing.getId());
        upd.setDescription(req.getDescription());
        if (req.getRules() != null) {
            upd.setRules(jsonMapper.writeValueAsString(new RulesPayload(req.getRules())));
        }
        upd.setUpdatedAt(new Date(System.currentTimeMillis()));
        templateMapper.updateByPrimaryKeySelective(upd);

        PlatformRbacTemplate reloaded = templateMapper.selectByPrimaryKey(existing.getId());
        if (req.getRules() != null) {
            provisioning.syncTemplateToAllEnabledClusters(reloaded);
        }
        return toVO(reloaded);
    }

    /**
     * 删除：内置保护 + 引用保护，软删后 best-effort 清理各集群 ClusterRole
     */
    public void delete(TemplateKeyRequest req) {
        PlatformRbacTemplate template = require(req.getId());
        if (template.getBuiltIn() == 1) {
            throw new CloudPlatformException(EnumResponseType.RBAC_TEMPLATE_BUILTIN_PROTECTED);
        }
        if (allocationMapper.countByRoleTemplate(template.getId()) > 0) {
            throw new CloudPlatformException(EnumResponseType.RBAC_TEMPLATE_IN_USE);
        }
        Date now = new Date(System.currentTimeMillis());
        templateMapper.softDelete(template.getId(), now);
        provisioning.deleteTemplateClusterRoleEverywhere(template);
        log.info("RBAC 模板 {} 已删除", template.getId());
    }

    private void validateRules(List<PolicyRuleDTO> rules) {
        if (rules == null || rules.isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "rules 不能为空");
        }
        for (PolicyRuleDTO rule : rules) {
            if (rule.getVerbs() == null || rule.getVerbs().isEmpty()) {
                throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "每条规则的 verbs 不能为空");
            }
            boolean hasResources = rule.getResources() != null && !rule.getResources().isEmpty();
            boolean hasUrls = rule.getNonResourceURLs() != null && !rule.getNonResourceURLs().isEmpty();
            if (!hasResources && !hasUrls) {
                throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                        "每条规则 resources / nonResourceURLs 至少填一项");
            }
        }
    }

    private PlatformRbacTemplate require(String id) {
        PlatformRbacTemplate template = templateMapper.selectByPrimaryKey(id);
        if (template == null) {
            throw new CloudPlatformException(EnumResponseType.RBAC_TEMPLATE_NOT_EXIST);
        }
        return template;
    }

    private RbacTemplateVO toVO(PlatformRbacTemplate template) {
        RbacTemplateVO vo = new RbacTemplateVO();
        vo.setId(template.getId());
        vo.setName(template.getName());
        vo.setDescription(template.getDescription());
        vo.setBuiltIn(template.getBuiltIn());
        vo.setCreatedAt(template.getCreatedAt());
        vo.setUpdatedAt(template.getUpdatedAt());
        if (StringUtils.hasText(template.getRules())) {
            vo.setRules(provisioning.parseRules(template.getRules()));
        }
        return vo;
    }
}
