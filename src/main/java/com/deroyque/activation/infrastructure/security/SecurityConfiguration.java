package com.deroyque.activation.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource Server OAuth 2.0: aceita JWT emitido pelo servidor de autorização corporativo
 * (fluxo client credentials), validando assinatura (JWKS), emissor, audiência e expiração conforme
 * {@code spring.security.oauth2.resourceserver.jwt.*}. Autorização por escopo:
 * {@code activation:write} cria, {@code activation:read} consulta.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

	static final String SCOPE_READ = "SCOPE_activation:read";
	static final String SCOPE_WRITE = "SCOPE_activation:write";

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
		var problemHandler = new ProblemDetailsSecurityHandler(objectMapper);
		http
				// API stateless autenticada por bearer token: não há sessão nem cookie para proteger contra CSRF.
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						// Actuator roda em porta de gerenciamento separada, não exposta publicamente.
						.requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll()
						.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/requests").hasAuthority(SCOPE_WRITE)
						.requestMatchers(HttpMethod.GET, "/api/v1/requests", "/api/v1/requests/*").hasAuthority(SCOPE_READ)
						.anyRequest().authenticated())
				.oauth2ResourceServer(oauth2 -> oauth2
						.jwt(Customizer.withDefaults())
						.authenticationEntryPoint(problemHandler)
						.accessDeniedHandler(problemHandler))
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(problemHandler)
						.accessDeniedHandler(problemHandler));
		return http.build();
	}

}
