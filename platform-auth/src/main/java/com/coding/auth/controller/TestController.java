package com.coding.auth.controller;

import com.coding.common.components.jwt.JwtComponent;
import com.coding.common.utils.ULIDGenerator;
import com.coding.data.models.auth.PlatformUser;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.data.models.system.SecurityUser;
import com.coding.data.models.system.TokenUserInfo;
import com.nimbusds.jose.JOSEException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.text.ParseException;

@RestController
public class TestController {

    @Autowired
    private JwtComponent<K8sCluster> jwtComponent;

    @Autowired
    private JwtComponent<TokenUserInfo> platformUserJwtComponent;

    @Autowired
    private JsonMapper jsonMapper;

    @GetMapping("/test")
    public void test () throws NoSuchAlgorithmException, InvalidKeySpecException, JOSEException, ParseException {



    };

    public static void main(String[] args) {
        System.out.println(ULIDGenerator.generateULID());
    }
}
