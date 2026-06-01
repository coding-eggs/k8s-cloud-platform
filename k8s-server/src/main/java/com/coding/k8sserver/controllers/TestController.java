package com.coding.k8sserver.controllers;

import com.coding.common.components.jwt.JwtComponent;
import com.coding.data.models.k8s.K8sCluster;


import com.coding.data.models.system.SecurityUser;
import com.nimbusds.jose.JOSEException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.text.ParseException;

@RestController
public class TestController {

    @Autowired
    private JwtComponent<SecurityUser> jwtComponent;

    @GetMapping("/test")
    public String test() {
        Jwt jwt = (Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        System.out.println("Authentication: " + jwt);
        return "hello world";
    }

    @GetMapping("/callback")
    public void callback(@RequestParam(value = "code", required = false) String code, HttpServletResponse response) throws IOException {
        System.out.println("Authorization code: " + code);

        String target = (code != null)
                ? "https://www.baidu.com?code=" + code
                : "https://www.baidu.com?error=no_code";

        response.setStatus(HttpServletResponse.SC_MOVED_TEMPORARILY);  // 302
        response.setHeader("Location", target);
        // 可选：response.flushBuffer();
    }

}
