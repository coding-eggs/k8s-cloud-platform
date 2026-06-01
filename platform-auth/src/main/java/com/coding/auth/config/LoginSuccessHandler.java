package com.coding.auth.config;


import com.coding.common.models.system.ResponseData;
import com.coding.data.mapper.auth.PlatformUserMapper;
import com.coding.data.models.system.SecurityUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class LoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {


    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private PlatformUserMapper userMapper;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response, Authentication authentication)
            throws IOException {
        // 获取最初想访问的页面
        DefaultSavedRequest defaultSavedRequest = (DefaultSavedRequest) request.getSession().getAttribute("SPRING_SECURITY_SAVED_REQUEST");
        Map<String,Object> map = new HashMap<>();

        SecurityUser securityUser = (SecurityUser) authentication.getPrincipal();
        //更新用户最后登录时间
        userMapper.updateLastLoginTime(securityUser.getUsername(),LocalDateTime.now());
        map.put("userinfo",securityUser);

        // 常规登录跳转
        if(ObjectUtils.isEmpty(defaultSavedRequest) || "/".equals(defaultSavedRequest.getRequestURI())
                || "undefined".equals(defaultSavedRequest.getRequestURI()) ){
            map.put("requestURI","/client-manage.html");
        }else{
            if (defaultSavedRequest.getRequestURI().startsWith("/.well-known")) {
                map.put("requestURI","/client-manage.html");
            } else {
                map.put("requestURI",defaultSavedRequest.getRedirectUrl());
            }
        }

        ResponseData<Map<String,Object>> responseData = new ResponseData<>(map);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(jsonMapper.writeValueAsString(responseData));

    }


}
