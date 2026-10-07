package com.ai.assistant.security;

import java.util.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.*;

@Configuration
public class WebConfig {
  @Bean
  org.springframework.security.authentication.AuthenticationManager authenticationManager() {
    return authentication -> {
      throw new org.springframework.security.authentication.BadCredentialsException(
          "Use the account login endpoint");
    };
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, AuthenticationFilter auth, AuthProperties props)
      throws Exception {
    var csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
    csrfRepo.setCookieCustomizer(c -> c.sameSite("Lax").secure(props.isCookieSecure()).path("/"));
    return http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(
            c ->
                c.csrfTokenRepository(csrfRepo)
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                    // JWT validation authenticates each request; rotate CSRF only at actual
                    // login/logout.
                    .sessionAuthenticationStrategy(
                        new org.springframework.security.web.authentication.session
                            .NullAuthenticatedSessionStrategy())
                    .requireCsrfProtectionMatcher(
                        req -> {
                          if (Set.of("GET", "HEAD", "OPTIONS").contains(req.getMethod()))
                            return false;
                          boolean cookie = false;
                          if (req.getCookies() != null)
                            for (var x : req.getCookies())
                              if (Set.of("ao_user", "ao_staff").contains(x.getName()))
                                cookie = true;
                          return cookie || req.getHeader("Authorization") == null;
                        }))
        .headers(
            h ->
                h.contentTypeOptions(c -> {})
                    .frameOptions(f -> f.deny())
                    .contentSecurityPolicy(
                        c ->
                            c.policyDirectives(
                                "default-src 'self'; script-src 'self'; style-src 'self'"
                                    + " 'unsafe-inline'; img-src 'self' data:; connect-src 'self';"
                                    + " frame-ancestors 'none'; object-src 'none'; base-uri"
                                    + " 'self'")))
        .authorizeHttpRequests(
            a ->
                a.dispatcherTypeMatchers(
                        jakarta.servlet.DispatcherType.ASYNC, jakarta.servlet.DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/",
                        "/chat",
                        "/chat/",
                        "/chat/index.html",
                        "/admin",
                        "/admin/",
                        "/admin/index.html",
                        "/platform",
                        "/platform/",
                        "/platform/index.html",
                        "/assets/**",
                        "/auth/csrf",
                        "/actuator/health",
                        "/actuator/health/**")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST, "/auth/login", "/auth/register", "/admin/login")
                    .permitAll()
                    .requestMatchers("/actuator/prometheus")
                    .hasAnyRole("MONITOR", "PLATFORM_ADMIN")
                    .requestMatchers("/platform/**")
                    .hasRole("PLATFORM_ADMIN")
                    .requestMatchers("/admin/**")
                    .hasAnyRole("OWNER", "STAFF", "PLATFORM_ADMIN")
                    .requestMatchers(
                        "/dish/**", "/order/**", "/user/**", "/chat", "/merchants", "/merchants/*")
                    .hasRole("CUSTOMER")
                    .requestMatchers("/auth/me", "/auth/logout", "/auth/password")
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, x) ->
                            AuthenticationFilter.write(
                                res, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请先登录"))
                    .accessDeniedHandler(
                        (req, res, x) ->
                            AuthenticationFilter.write(
                                res,
                                HttpStatus.FORBIDDEN,
                                x instanceof org.springframework.security.web.csrf.CsrfException
                                    ? "CSRF_REJECTED"
                                    : "FORBIDDEN",
                                "权限不足或CSRF校验失败")))
        .addFilterBefore(auth, UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  @Bean
  FilterRegistrationBean<AuthenticationFilter> authRegistration(AuthenticationFilter filter) {
    var registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }
}
