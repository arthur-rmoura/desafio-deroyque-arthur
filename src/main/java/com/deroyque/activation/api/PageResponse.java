package com.deroyque.activation.api;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** Contrato estável de paginação, sem expor a estrutura interna do {@link Page} do Spring Data. */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

	static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
		return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
				page.getTotalElements(), page.getTotalPages());
	}

}
