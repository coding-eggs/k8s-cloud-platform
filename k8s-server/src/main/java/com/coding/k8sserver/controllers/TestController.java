package com.coding.k8sserver.controllers;


import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.math.BigInteger;

@RestController
public class TestController {

    public static void main(String[] args) {

        BigInteger a = BigInteger.valueOf(1L <<31);
        BigInteger b = BigInteger.valueOf((long) Math.pow(3, 21));

        System.out.println(a.toString());
        System.out.println(b.toString());
        System.out.println(a.compareTo(b));

    }

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
