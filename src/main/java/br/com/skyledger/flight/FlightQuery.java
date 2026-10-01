package br.com.skyledger.flight;

import java.time.LocalDate;

/**
 * Consulta de voos já validada.
 *
 * @param airport   código ICAO do aeroporto
 * @param direction sentido dos voos
 * @param from      data inicial inclusiva da partida de referência, ou {@code null}
 * @param to        data final inclusiva da partida de referência, ou {@code null}
 * @param status    situação ({@code REALIZADO} ou {@code CANCELADO}), ou {@code null} para todas
 * @param page      página, a partir de 0
 * @param size      voos por página, de 1 a {@value FlightQueryParameters#MAX_SIZE}
 * @param sort      campo de ordenação
 * @param ascending {@code true} para ordem crescente
 */
public record FlightQuery(String airport, Direction direction, LocalDate from, LocalDate to, String status,
        int page, int size, SortField sort, boolean ascending) {

    /**
     * Ordenação aplicada, no formato do parâmetro {@code sort}.
     *
     * @return por exemplo {@code departure,asc}
     */
    public String sortDescription() {
        return sort.parameterName() + "," + (ascending ? "asc" : "desc");
    }
}
