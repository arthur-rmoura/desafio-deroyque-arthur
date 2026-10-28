package com.deroyque.activation.application;

/**
 * Provedor externo de ativação. A implementação traduz o protocolo do provedor (HTTP, códigos de
 * status, timeouts) em um {@link ProviderResult}, sem lançar exceções para falhas esperadas.
 */
public interface ActivationProvider {

	ProviderResult activate(ProviderActivation activation);

}
