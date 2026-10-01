package br.com.skyledger.flight;

import java.util.List;

/**
 * Página de voos da consulta.
 *
 * @param content       voos da página
 * @param page          página retornada, a partir de 0
 * @param size          tamanho de página solicitado
 * @param totalElements total de voos que atendem aos filtros
 * @param totalPages    total de páginas
 * @param sort          ordenação aplicada ({@code campo,direção})
 */
public record FlightPage(List<FlightView> content, int page, int size, long totalElements, int totalPages,
        String sort) {
}
