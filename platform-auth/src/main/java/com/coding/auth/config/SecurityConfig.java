package com.coding.auth.config;


import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.system.ResponseData;
import com.coding.data.mapper.auth.PlatformRoleMapper;
import com.coding.data.mapper.auth.PlatformUserMapper;

import com.coding.data.models.auth.PlatformUser;
import com.coding.data.models.system.SecurityRole;
import com.coding.data.models.system.SecurityUser;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.session.InvalidSessionStrategy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private PlatformUserMapper userMapper;

    @Autowired
    private PlatformRoleMapper roleMapper;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private LoginSuccessHandler loginSuccessHandler;


    @Data
    @Configuration
    @ConfigurationProperties(prefix = "spring.security")
    public static class Properties {
        private String[] ignoreUrls;
    }




    // @formatter:off
    @Bean
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http, Properties properties) throws Exception {
        http
                .authorizeHttpRequests(authorize ->
                        authorize
                                .requestMatchers(properties.getIgnoreUrls()).permitAll()
                                .anyRequest().authenticated()
                )
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(form -> form.loginPage("/login.html")
                                .loginProcessingUrl("/user/login")
                                .usernameParameter("username")
                                .passwordParameter("password")
                        .successHandler(loginSuccessHandler))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            log.info("token丢失，未登录");
                            String acceptHeader = request.getHeader("Accept");
                            String contentType = request.getContentType();

                            if (isJsonRequest(acceptHeader, contentType)) {
                                //设置响应头为重定向
                                ResponseData<String> responseData = new ResponseData<>();
                                responseData.setCode(EnumResponseType.USER_UN_LOGIN.getCode());
                                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                                response.getWriter().write(jsonMapper.writeValueAsString(responseData));
                            }else {
                                LoginUrlAuthenticationEntryPoint authenticationEntryPoint = new LoginUrlAuthenticationEntryPoint("/login.html");
                                authenticationEntryPoint.commence(request,response,authException);
                            }
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            ResponseData<String> responseData = new ResponseData<>();
                            responseData.setCode(EnumResponseType.NON_AUTH_ENTRY_POINT.getCode());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            response.setHeader("Access-Control-Allow-Origin", "*");
                            response.setHeader("Cache-Control","no-cache");
                            response.getWriter().write(jsonMapper.writeValueAsString(responseData));
                        }))
                .sessionManagement(session ->
                                session.invalidSessionStrategy((request,response)-> {
                                    log.info("session 过期，跳转登录。。。");
                                    String acceptHeader = request.getHeader("Accept");
                                    String contentType = request.getContentType();

                                    if (isJsonRequest(contentType, acceptHeader)) {
                                        //设置响应头为重定向
                                        ResponseData<String> responseData = new ResponseData<>();
                                        responseData.setCode(EnumResponseType.USER_SESSION_EXPIRED.getCode());
                                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                                        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                                        response.getWriter().write(jsonMapper.writeValueAsString(responseData));
                                    }else {
                                        log.info("session 不可用 重定向到登录页");
                                        //清除session
                                        Cookie cookie = new Cookie("JSESSIONID",null);
                                        cookie.setMaxAge(0);
                                        cookie.setPath("/");
                                        response.addCookie(cookie);
                                        response.sendRedirect(request.getContextPath() + "/login.html");
                                    }
                                })
                                .maximumSessions(-1)
                                .expiredSessionStrategy(event -> {
                                    HttpServletRequest request = event.getRequest();
                                    HttpServletResponse response = event.getResponse();
                                    String acceptHeader = request.getHeader("Accept");
                                    String contentType = request.getContentType();

                                    if (isJsonRequest(contentType, acceptHeader)) {
                                        //设置响应头为重定向
                                        ResponseData<String> responseData = new ResponseData<>();
                                        responseData.setCode(EnumResponseType.USER_SESSION_EXPIRED.getCode());
                                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                                        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                                        response.getWriter().write(jsonMapper.writeValueAsString(responseData));
                                    }else {
                                        log.info("session 过期 重定向到登录页");
                                        //清除session
                                        Cookie cookie = new Cookie("JSESSIONID",null);
                                        cookie.setMaxAge(0);
                                        cookie.setPath("/");
                                        response.addCookie(cookie);
                                        response.sendRedirect(request.getContextPath() + "/login.html");
                                    }
                                })

                );



        http.cors(Customizer.withDefaults());

        return http.build();
    }

    // 判断是否是 JSON 请求
    private boolean isJsonRequest(String contentType, String acceptHeader) {
        return (contentType != null && contentType.contains("application/json")) ||
                (acceptHeader != null && acceptHeader.contains("application/json"));
    }

    @Bean
    public AuthenticationProvider authorizationManager(UserDetailsService userDetailsService) {

        DaoAuthenticationProvider authenticationProvider = new DaoAuthenticationProvider(userDetailsService);
        authenticationProvider.setPasswordEncoder(new BCryptPasswordEncoder());
        return authenticationProvider;
    }


    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        // allowCredentials=true 时 allowedOrigins 不能含字面量 "*"，用 pattern 回显请求 Origin
        config.addAllowedOriginPattern("*");
        config.setAllowCredentials(true);
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return username-> {
            PlatformUser user = userMapper.selectByUsername(username);
            if (user == null) {
                throw new UsernameNotFoundException("未找到用户");
            }
            if (user.getStatus() == null || user.getStatus() != 1) {
                throw new DisabledException("用户已被禁用");
            }
            List<SecurityRole> securityRoles = roleMapper.selectTenantRole(user.getId());

            return new SecurityUser(user.getUsername(), user.getPassword() ,
                    true, true, true, true,
                    securityRoles, user.getId(), user.getDisplayName(), user.getEmail(), user.getStatus(),
                    user.getType(), user.getSource());
        };
    }
}
