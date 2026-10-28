package com.deroyque.activation.infrastructure.provider;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
class ProviderClientConfiguration {

	static final String API_KEY_HEADER = "X-Api-Key";

	/**
	 * Usa o {@link RestClient.Builder} do Spring Boot para herdar a instrumentação de métricas
	 * ({@code http.client.requests}: latência e taxa de erro do provedor).
	 */
	@Bean
	RestClient providerRestClient(RestClient.Builder builder, ProviderProperties properties) {
		var httpClient = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(properties.connectTimeout())
				.build();
		var requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.readTimeout());

		return builder
				.baseUrl(properties.baseUrl().toString())
				.requestFactory(requestFactory)
				.defaultHeader(API_KEY_HEADER, properties.apiKey())
				.build();
	}

}
