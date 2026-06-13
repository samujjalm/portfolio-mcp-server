package de.samujjal.java_net.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * spring-boot-starter-security locks down every endpoint by default, which would
 * block the MCP transport. For this local/learning service we permit all requests
 * and disable CSRF on the MCP endpoints (the SSE stream and the JSON-RPC POST
 * message channel). Tighten this — real authentication, scoped to the MCP paths —
 * before exposing the server beyond localhost.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.ignoringRequestMatchers("/sse", "/mcp/**"));
        return http.build();
    }
}
