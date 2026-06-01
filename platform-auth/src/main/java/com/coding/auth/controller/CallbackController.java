package com.coding.auth.controller;


import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CallbackController {



    @GetMapping("/callback")
    public String callback(@RequestParam(value = "code", required = false) String code) {

        System.out.println(code);
        return "redirect:https://www.baidu.com?code=" + code;
    }


}
