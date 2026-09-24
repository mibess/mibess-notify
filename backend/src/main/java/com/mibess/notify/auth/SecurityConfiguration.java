package com.mibess.notify.auth;

import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

  @Bean
  PasswordEncoder encoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  SecurityContextRepository contextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  UserDetailsService users(JdbcClient db) {
    return email ->
      db
        .sql(
          "SELECT u.email,u.password_hash,u.enabled,m.role FROM users u JOIN workspace_memberships m ON m.user_id=u.id WHERE u.email=:email ORDER BY m.workspace_id LIMIT 1"
        )
        .param("email", email.toLowerCase())
        .query((r, n) ->
          User.withUsername(r.getString("email"))
            .password(r.getString("password_hash"))
            .roles(r.getString("role"))
            .disabled(!r.getBoolean("enabled"))
            .build()
        )
        .optional()
        .orElseThrow(() -> new UsernameNotFoundException("Login inválido"));
  }

  @Bean
  SecurityFilterChain security(
    HttpSecurity http,
    SecurityContextRepository context
  ) throws Exception {
    var csrf = new CookieCsrfTokenRepository();
    csrf.setCookieHttpOnly(false);
    csrf.setCookiePath("/");
    http
      .securityContext(c -> c.securityContextRepository(context))
      .csrf(c ->
        c
          .csrfTokenRepository(csrf)
          .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
          .ignoringRequestMatchers(
            "/api/v1/events",
            "/webhooks/meta/whatsapp",
            "/webhooks/evolution/whatsapp/*"
          )
      )
      .authorizeHttpRequests(a ->
        a
          .requestMatchers(
            "/api/v1/auth/csrf",
            "/api/v1/auth/login",
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness",
            "/webhooks/meta/whatsapp",
            "/webhooks/evolution/whatsapp/*"
          )
          .permitAll()
          .requestMatchers(HttpMethod.POST, "/api/v1/events")
          .permitAll()
          .requestMatchers("/actuator/**")
          .hasRole("ADMIN")
          .anyRequest()
          .authenticated()
      )
      .exceptionHandling(e ->
        e
          .authenticationEntryPoint((req, res, ex) -> res.sendError(401))
          .accessDeniedHandler((req, res, ex) -> res.sendError(403))
      )
      .logout(l ->
        l
          .logoutUrl("/api/v1/auth/logout")
          .logoutSuccessHandler((req, res, auth) -> res.setStatus(204))
          .invalidateHttpSession(true)
          .deleteCookies("SESSION", "XSRF-TOKEN")
      );
    return http.build();
  }
}
