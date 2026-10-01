package br.com.skyledger.flight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.Test;

/**
 * Testes unitários da validação dos parâmetros de {@code GET /flights}.
 */
class FlightQueryParametersTest {

    /** Sem parâmetros opcionais: BOTH, página 0, 20 por página, partida crescente. */
    @Test
    void appliesDefaults() {
        FlightQuery query = parse("SBGR", null, null, null, null, null, null, null);

        assertThat(query.airport()).isEqualTo("SBGR");
        assertThat(query.direction()).isEqualTo(Direction.BOTH);
        assertThat(query.from()).isNull();
        assertThat(query.status()).isNull();
        assertThat(query.page()).isZero();
        assertThat(query.size()).isEqualTo(20);
        assertThat(query.sortDescription()).isEqualTo("departure,asc");
    }

    /** Todos os parâmetros válidos. */
    @Test
    void parsesAllParameters() {
        FlightQuery query = parse("SBGR", "ARRIVAL", "2026-05-01", "2026-05-31", "CANCELADO", "3", "100",
                "delay,desc");

        assertThat(query.direction()).isEqualTo(Direction.ARRIVAL);
        assertThat(query.from()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(query.to()).isEqualTo(LocalDate.of(2026, 5, 31));
        assertThat(query.status()).isEqualTo("CANCELADO");
        assertThat(query.page()).isEqualTo(3);
        assertThat(query.size()).isEqualTo(100);
        assertThat(query.sort()).isEqualTo(SortField.DELAY);
        assertThat(query.ascending()).isFalse();
    }

    /** sort sem direção é crescente. */
    @Test
    void sortWithoutDirectionIsAscending() {
        assertThat(parse("SBGR", null, null, null, null, null, null, "airline").sortDescription())
                .isEqualTo("airline,asc");
    }

    /** Cenário "Código IATA recusado". */
    @Test
    void rejectsIataCode() {
        rejects("airport", "GRU", null, null, null, null, null, null, null)
                .hasMessageContaining("ICAO de 4 caracteres").hasMessageContaining("GRU");
    }

    /** Cenário "Aeroporto ausente" e formatos inválidos. */
    @Test
    void rejectsMissingOrMalformedAirport() {
        rejects("airport", null, null, null, null, null, null, null, null).hasMessageContaining("obrigatório");
        rejects("airport", "sbgr", null, null, null, null, null, null, null).hasMessageContaining("sbgr");
        rejects("airport", "SBGRX", null, null, null, null, null, null, null);
    }

    /** Cenário "Período invertido". */
    @Test
    void rejectsInvertedPeriod() {
        rejects("from", "SBGR", null, "2026-06-01", "2026-05-01", null, null, null, null)
                .hasMessageContaining("from").hasMessageContaining("to");
    }

    /** Cenário "Data mal formada", incluindo data inexistente. */
    @Test
    void rejectsMalformedDates() {
        rejects("from", "SBGR", null, "11/05/2026", null, null, null, null, null)
                .hasMessageContaining("AAAA-MM-DD");
        rejects("to", "SBGR", null, null, "2026-02-30", null, null, null, null).hasMessageContaining("to");
    }

    /** Cenário "Valor de enum inválido" para direction e status. */
    @Test
    void rejectsInvalidEnums() {
        rejects("direction", "SBGR", "NORTH", null, null, null, null, null, null)
                .hasMessageContaining("DEPARTURE, ARRIVAL, BOTH");
        rejects("status", "SBGR", null, null, null, "ATRASADO", null, null, null)
                .hasMessageContaining("REALIZADO, CANCELADO");
    }

    /** Cenário "Tamanho acima do máximo", tamanho zero e página negativa. */
    @Test
    void rejectsOutOfRangePaging() {
        rejects("size", "SBGR", null, null, null, null, null, "101", null).hasMessageContaining("100");
        rejects("size", "SBGR", null, null, null, null, null, "0", null);
        rejects("page", "SBGR", null, null, null, null, "-1", null, null);
        rejects("page", "SBGR", null, null, null, null, "um", null, null).hasMessageContaining("inteiro");
    }

    /** Cenário "Campo de ordenação desconhecido" e direção inválida. */
    @Test
    void rejectsInvalidSort() {
        rejects("sort", "SBGR", null, null, null, null, null, null, "passengers,asc")
                .hasMessageContaining("departure, arrival, airline, flightNumber, delay");
        rejects("sort", "SBGR", null, null, null, null, null, null, "delay,up").hasMessageContaining("asc|desc");
    }

    private static FlightQuery parse(String airport, String direction, String from, String to, String status,
            String page, String size, String sort) {
        return FlightQueryParameters.parse(airport, direction, from, to, status, page, size, sort);
    }

    private static AbstractThrowableAssert<?, ? extends Throwable> rejects(String parameter, String airport,
            String direction, String from, String to, String status, String page, String size, String sort) {
        return assertThatThrownBy(() -> parse(airport, direction, from, to, status, page, size, sort))
                .isInstanceOf(InvalidQueryParameterException.class)
                .satisfies(ex -> assertThat(((InvalidQueryParameterException) ex).parameter()).isEqualTo(parameter))
                .hasMessageContaining(parameter);
    }
}
