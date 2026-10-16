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
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.session.Session;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
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
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http, Properties properties,
                                                                              SessionRegistry sessionRegistry) throws Exception {
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

                                // ⚠️ 这一行是「改密码后按用户名踢掉其它会话」的**全部前提**。
                                //    光配 maximumSessions(-1) 是不够的：没给 sessionRegistry 时 Spring 用
                                //    默认的**内存版** SessionRegistryImpl，它**不会**往会话里写 principal 索引
                                //    属性 —— 于是 Redis 里一个 spring:session:index:* 都不会有
                                //    （2026-10-16 在线上查过：21 个 sessions key、0 个 index key），
                                //    findByPrincipalName 永远返回空。换成 SpringSessionBackedSessionRegistry
                                //    才会在登录那一刻把索引写进 Redis。
                                //    maximumSessions(-1) = 不限并发数：我们只要它**登记**，不要它踢人。
                                .sessionRegistry(sessionRegistry)

                )
                // 登出：SPA 侧「退出登录」必须打到这里，否则根凭证（HttpOnly 会话）仍在，
                // 再点登录会被 /oauth2/authorize 静默 SSO 回原用户 —— 表现为「换不了用户」。
                // LogoutFilter 排在授权过滤器之前，会话已过期时重复调用是幂等 no-op（不报错）。
                .logout(logout -> logout
                        .logoutUrl("/session/logout")
                        // SecurityContextLogoutHandler（默认）invalidate 掉 Redis 会话；Spring Session 会在
                        // 响应提交时把 SESSION cookie 置为过期。这里再显式清一次同名 cookie 兜底——
                        // 默认的 CookieClearingLogoutHandler 清的是 JSESSIONID，而本应用的会话 cookie 名是 SESSION。
                        .addLogoutHandler(new CookieClearingLogoutHandler("SESSION"))
                        .logoutSuccessHandler((request, response, authentication) -> {
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            response.setHeader("Cache-Control", "no-cache");
                            response.getWriter().write(jsonMapper.writeValueAsString(new ResponseData<>()));
                        }));

        http.cors(Customizer.withDefaults());

        return http.build();
    }

    // 判断是否是 JSON 请求
    private boolean isJsonRequest(String contentType, String acceptHeader) {
        return (contentType != null && contentType.contains("application/json")) ||
                (acceptHeader != null && acceptHeader.contains("application/json"));
    }

    /**
     * 会话登记表：**按用户名**索引会话的那一套（自助改密码踢会话靠它）。
     *
     * <p>⚠️ 别改回默认的内存版 {@code SessionRegistryImpl}：它只在堆里记，**不会**往会话里写
     * {@code FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME} 这个属性，
     * 于是 Redis 里没有 {@code spring:session:index:*}、{@code findByPrincipalName} 恒空 ——
     * 「改密码把别的设备踢下去」会静默地一个都踢不到（2026-10-16 线上核对过：0 个 index key）。
     *
     * <p>⚠️ 索引是**登录那一刻**写进去的，所以本功能上线之前就存在的会话不在索引里、踢不到。
     */
    @Bean
    public SpringSessionBackedSessionRegistry<? extends Session> sessionRegistry(
            RedisIndexedSessionRepository sessionRepository) {
        return new SpringSessionBackedSessionRegistry<>(sessionRepository);
    }

    @Bean
    public AuthenticationProvider authorizationManager(UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {

        DaoAuthenticationProvider authenticationProvider = new DaoAuthenticationProvider(userDetailsService);
        // ⚠️ 用容器里那一个（`AuthorizationServerConfig#passwordEncoder`），别在这儿 new ——
        //    这个类里原本就是 `new BCryptPasswordEncoder()`，而我在 S1.1 里想当然地又加了一个同名
        //    `@Bean passwordEncoder()`，结果**启动直接失败**（bean 名冲突，overriding 是关的）。
        //    这就是"单测绿 ≠ 装得起来"：8 条单测全过，启动才发现。
        authenticationProvider.setPasswordEncoder(passwordEncoder);
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
