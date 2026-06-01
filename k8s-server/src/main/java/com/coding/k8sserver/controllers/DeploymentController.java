package com.coding.k8sserver.controllers;

import com.coding.common.models.k8s.dto.DeploymentDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8sserver.annotations.TenantValidate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/deployment")
public class DeploymentController {


    @PostMapping("/create")
    @TenantValidate
    public ResponseData<DeploymentDTO> create(@RequestBody DeploymentDTO deployment) {

        
        return null;
    }

}

