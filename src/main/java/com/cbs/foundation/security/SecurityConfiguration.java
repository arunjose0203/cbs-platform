package com.cbs.foundation.security;

import jakarta.servlet.http.HttpServletResponse;
import com.cbs.access.AccessService;
import com.cbs.access.AccessPolicyFilter;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@org.springframework.boot.context.properties.EnableConfigurationProperties(RateLimitFilter.Settings.class)
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, IdentityConfiguration.Settings settings,
            ObjectProvider<JwtDecoder> decoders, ObjectProvider<AccessService> access, RateLimitFilter.Settings limits,
            @Value("${server.address:127.0.0.1}") String bindAddress) throws Exception {
        if (settings.allowLocalHttp() && !List.of("127.0.0.1", "localhost", "::1").contains(bindAddress)) {
            throw new IllegalStateException("Local HTTP identity mode requires CBS to bind to loopback");
        }
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self' https:"
                                + settings.localConnectOrigin() + "; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"))
                        .referrerPolicy(referrer -> referrer.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .addHeaderWriter(new org.springframework.security.web.header.writers.StaticHeadersWriter(
                                "Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()"))
                        .httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31_536_000).includeSubDomains(true)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET,"/","/admin","/admin/","/admin/index.html","/admin/app.js","/admin/styles.css","/admin/chart.css","/portal/config").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness",
                                "/actuator/health/readiness").permitAll()
                        .requestMatchers(HttpMethod.POST,"/api/v1/payments/mpesa/callback/*/*").permitAll()
                        .requestMatchers("/api/v1/admin/**").access((authentication, context) ->
                                new org.springframework.security.authorization.AuthorizationDecision(
                                        settings.enabled() && authentication.get().getAuthorities().stream()
                                                .anyMatch(a -> a.getAuthority().equals("ROLE_PLATFORM_ADMIN"))))
                        .requestMatchers("/api/v1/banks/**","/api/v1/access/**").access((authentication, context) ->
                                new org.springframework.security.authorization.AuthorizationDecision(
                                        settings.enabled() && authentication.get() instanceof
                                                org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken))
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                writeProblem(response, 401, "Authentication required", "AUTHENTICATION_REQUIRED"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeProblem(response, 403, "Access denied", "ACCESS_DENIED")));
        // Throttle before any token is parsed, after security headers are written.
        http.addFilterAfter(new RateLimitFilter(limits), org.springframework.security.web.header.HeaderWriterFilter.class);
        if (settings.enabled()) {
            JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
            converter.setJwtGrantedAuthoritiesConverter(jwt -> {
                Object roles = jwt.getClaims().get("roles");
                return roles instanceof List<?> list && list.contains("PLATFORM_ADMIN")
                        ? List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")) : List.of();
            });
            http.oauth2ResourceServer(resource -> resource
                    .jwt(jwt -> jwt.decoder(decoders.getObject()).jwtAuthenticationConverter(converter))
                    .authenticationEntryPoint((request, response, exception) ->
                            writeProblem(response, 401, "Authentication required", "AUTHENTICATION_REQUIRED"))
                    .accessDeniedHandler((request, response, exception) ->
                            writeProblem(response, 403, "Access denied", "ACCESS_DENIED")));
            http.addFilterAfter(new AccessPolicyFilter(access),BearerTokenAuthenticationFilter.class);
        }
        return http.build();
    }

    @Bean
    UserDetailsService closedUserDirectory() {
        // Suppress generated users: only verified bearer tokens can authenticate.
        return username -> { throw new UsernameNotFoundException("Identity integration is not configured"); };
    }

    private static void writeProblem(HttpServletResponse response, int status, String title, String code)
            throws IOException {
        response.setStatus(status);
        if (status == 401) { response.setHeader("WWW-Authenticate", "Bearer"); }
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title
                + "\",\"status\":" + status + ",\"code\":\"" + code + "\"}");
    }
}
