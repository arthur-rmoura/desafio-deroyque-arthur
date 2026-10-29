package com.deroyque.activation.application;

/**
 * Dados de uma nova solicitação, já validados na borda.
 *
 * @param clientId sistema consumidor autenticado que está criando a solicitação
 */
public record NewActivationRequest(String clientId, String externalId, String customerId, String productCode) {
}
