package br.com.skyledger.flight;

import static br.com.skyledger.support.LocalHttp.get;
import static br.com.skyledger.support.LocalHttp.json;
import static br.com.skyledger.support.LocalHttp.send;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;

import br.com.skyledger.support.TestDatabase;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/**
 * Contrato HTTP de {@code GET /flights} sobre uma massa inserida por SQL no schema de testes.
 *
 * <p>Massa (aeroporto consultado: {@code SBGR}):
 * <ul>
 *   <li>{@code AZU 0001} SBGR→SBRJ, prevista 11/05 08:30, real 08:38 (atraso 8);</li>
 *   <li>{@code AZU 0002} SBGR→SBSP, prevista 23/05 21:35, real 21:27 (atraso -8);</li>
 *   <li>{@code GLO 0003} SBRJ→SBGR, cancelado, prevista 11/05 23:59;</li>
 *   <li>{@code GLO 0004} SBSP→SBGR, realizado sem prevista, real 12/05 00:00;</li>
 *   <li>{@code GLO 0005} SBGR→SBGR (origem igual ao destino), prevista 20/05 10:00, real 10:30 (atraso 30);</li>
 *   <li>{@code AZU 0006} SBRJ→SBSP (não envolve SBGR);</li>
 *   <li>250 voos {@code AZU 1000..1249} SBGR→SBRJ a partir de 01/06, atraso de 0 a 6 minutos.</li>
 * </ul>
 * Totais em SBGR: DEPARTURE 253, ARRIVAL 3, BOTH 255.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FlightQueryEndpointTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @MockitoSpyBean
    private FlightQueryRepository repository;

    @BeforeEach
    void seed() {
        TestDatabase.truncateAll(jdbc);
        jdbc.sql("INSERT INTO airport (icao) VALUES ('SBGR'), ('SBRJ'), ('SBSP')").update();
        jdbc.sql("INSERT INTO airline (icao) VALUES ('AZU'), ('GLO')").update();
        long batch = jdbc.sql("""
                INSERT INTO ingestion_batch (dataset, file_name, sha256, status, rows_read, inserted, updated,
                    duplicates, rejected, started_at, finished_at)
                VALUES ('VRA', 'teste.csv', repeat('0', 64), 'COMPLETED', 0, 0, 0, 0, 0, now(), now()) RETURNING id
                """).query(Long.class).single();
        flight(batch, "AZU", "0001", "SBGR", "SBRJ", "2026-05-11 08:30", "2026-05-11 08:38", "REALIZADO");
        flight(batch, "AZU", "0002", "SBGR", "SBSP", "2026-05-23 21:35", "2026-05-23 21:27", "REALIZADO");
        flight(batch, "GLO", "0003", "SBRJ", "SBGR", "2026-05-11 23:59", null, "CANCELADO");
        flight(batch, "GLO", "0004", "SBSP", "SBGR", null, "2026-05-12 00:00", "REALIZADO");
        flight(batch, "GLO", "0005", "SBGR", "SBGR", "2026-05-20 10:00", "2026-05-20 10:30", "REALIZADO");
        flight(batch, "AZU", "0006", "SBRJ", "SBSP", "2026-05-11 09:00", "2026-05-11 09:00", "REALIZADO");
        jdbc.sql("""
                INSERT INTO flight (airline_icao, flight_number, authorization_code, line_type, origin_icao,
                    destination_icao, scheduled_departure, actual_departure, scheduled_arrival, actual_arrival,
                    status, justification_code, batch_id)
                SELECT 'AZU', (1000 + n)::text, '0', 'N', 'SBGR', 'SBRJ',
                    timestamp '2026-06-01 00:00' + n * interval '1 hour',
                    timestamp '2026-06-01 00:00' + n * interval '1 hour' + (n % 7) * interval '1 minute',
                    timestamp '2026-06-01 01:00' + n * interval '1 hour',
                    timestamp '2026-06-01 01:00' + n * interval '1 hour' + (n % 7) * interval '1 minute',
                    'REALIZADO', 'N/A', :batch
                FROM generate_series(0, 249) AS n
                """).param("batch", batch).update();
    }

    /** Cenário "Voos que saem de um aeroporto". */
    @Test
    void returnsDeparturesOnly() {
        JsonNode body = ok("/flights?airport=SBGR&direction=DEPARTURE&size=100");

        assertThat(body.path("totalElements").asLong()).isEqualTo(253);
        assertThat(body.path("content").valueStream().map(f -> f.path("origin").asString())).containsOnly("SBGR");
    }

    /** Cenário "Sentido padrão": BOTH = DEPARTURE + ARRIVAL − origem igual ao destino. */
    @Test
    void defaultDirectionIsBothAndCountsSelfLoopOnce() {
        long both = ok("/flights?airport=SBGR").path("totalElements").asLong();
        long departures = ok("/flights?airport=SBGR&direction=DEPARTURE").path("totalElements").asLong();
        long arrivals = ok("/flights?airport=SBGR&direction=ARRIVAL").path("totalElements").asLong();

        assertThat(arrivals).isEqualTo(3);
        assertThat(both).isEqualTo(255).isEqualTo(departures + arrivals - 1);
    }

    /** Cenário "Período inclusivo": 11/05 traz 08:30 e 23:59, e não 12/05 00:00. */
    @Test
    void periodIsInclusiveByReferenceDeparture() {
        assertThat(numbers(ok("/flights?airport=SBGR&from=2026-05-11&to=2026-05-11")))
                .containsExactly("0001", "0003");
    }

    /** Cenário "Realizado sem horário previsto entra pelo horário real". */
    @Test
    void flightWithoutScheduleIsFilteredByActualDeparture() {
        assertThat(numbers(ok("/flights?airport=SBGR&from=2026-05-12&to=2026-05-12"))).containsExactly("0004");
        assertThat(numbers(ok("/flights?airport=SBGR&from=2026-06-11"))).doesNotContain("0004");
    }

    /** Cenário "Filtro de situação". */
    @Test
    void filtersByStatus() {
        JsonNode body = ok("/flights?airport=SBGR&status=CANCELADO");
        assertThat(numbers(body)).containsExactly("0003");
        assertThat(body.path("content").get(0).path("status").asString()).isEqualTo("CANCELADO");
    }

    /** Cenário "Primeira página padrão". */
    @Test
    void defaultFirstPage() {
        JsonNode body = ok("/flights?airport=SBGR");

        assertThat(body.path("content").size()).isEqualTo(20);
        assertThat(body.path("page").asInt()).isZero();
        assertThat(body.path("size").asInt()).isEqualTo(20);
        assertThat(body.path("totalPages").asInt()).isEqualTo(13);
        assertThat(body.path("sort").asString()).isEqualTo("departure,asc");
        assertThat(numbers(body).subList(0, 5)).containsExactly("0001", "0003", "0004", "0005", "0002");
    }

    /** Cenário "Páginas sem repetição", com ordenação de muitos empates (airline). */
    @Test
    void pagesHaveNoRepetition() {
        Set<String> seen = new HashSet<>();
        List<String> all = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            JsonNode body = ok("/flights?airport=SBGR&size=100&sort=airline,asc&page=" + page);
            body.path("content").valueStream()
                    .map(f -> f.path("airline").asString() + f.path("flightNumber").asString())
                    .forEach(id -> {
                        all.add(id);
                        seen.add(id);
                    });
        }
        assertThat(all).hasSize(255);
        assertThat(seen).hasSize(255);
    }

    /** Cenário "Ordenação por atraso decrescente": nulos por último. */
    @Test
    void sortsByDelayDescendingWithNullsLast() {
        JsonNode content = ok("/flights?airport=SBGR&sort=delay,desc&size=100&page=2").path("content");
        JsonNode first = ok("/flights?airport=SBGR&sort=delay,desc").path("content").get(0);

        assertThat(first.path("flightNumber").asString()).isEqualTo("0005");
        assertThat(first.path("departureDelayMinutes").asInt()).isEqualTo(30);
        List<JsonNode> lastPage = content.valueStream().toList();
        assertThat(lastPage.subList(lastPage.size() - 2, lastPage.size()))
                .allSatisfy(f -> assertThat(f.path("departureDelayMinutes").isNull()).isTrue());
        assertThat(ok("/flights?airport=SBGR&sort=delay,asc").path("content").get(0)
                .path("departureDelayMinutes").asInt()).isEqualTo(-8);
    }

    /** Cenário "Página além da última". */
    @Test
    void pageBeyondLastIsEmpty() {
        JsonNode body = ok("/flights?airport=SBGR&size=100&page=3");
        assertThat(body.path("content").size()).isZero();
        assertThat(body.path("totalElements").asLong()).isEqualTo(255);
    }

    /** Cenários "Voo realizado com atraso", "Partida adiantada" e "Sem atraso calculável". */
    @Test
    void representsTimesWithoutOffsetAndDelay() {
        JsonNode content = ok("/flights?airport=SBGR&from=2026-05-01&to=2026-05-31&size=100").path("content");
        JsonNode late = byNumber(content, "0001");
        assertThat(late.path("scheduledDeparture").asString()).isEqualTo("2026-05-11T08:30:00");
        assertThat(late.path("actualDeparture").asString()).isEqualTo("2026-05-11T08:38:00");
        assertThat(late.path("scheduledArrival").asString()).isEqualTo("2026-05-11T09:30:00");
        assertThat(late.path("departureDelayMinutes").asInt()).isEqualTo(8);
        assertThat(late.path("airline").asString()).isEqualTo("AZU");
        assertThat(late.path("destination").asString()).isEqualTo("SBRJ");

        assertThat(byNumber(content, "0002").path("departureDelayMinutes").asInt()).isEqualTo(-8);

        JsonNode cancelled = byNumber(content, "0003");
        assertThat(cancelled.path("departureDelayMinutes").isNull()).isTrue();
        assertThat(cancelled.path("actualDeparture").isNull()).isTrue();
        JsonNode noSchedule = byNumber(content, "0004");
        assertThat(noSchedule.path("departureDelayMinutes").isNull()).isTrue();
        assertThat(noSchedule.path("scheduledDeparture").isNull()).isTrue();
    }

    /** Cenários de erro 400: "Código IATA recusado", "Aeroporto ausente" e "Tamanho acima do máximo". */
    @Test
    void rejectsInvalidParameters() {
        problem(get(port, "/flights?airport=GRU"), 400, "ICAO de 4 caracteres");
        problem(get(port, "/flights"), 400, "airport");
        problem(get(port, "/flights?airport=SBGR&size=101"), 400, "100");
        problem(get(port, "/flights?airport=SBGR&from=11/05/2026"), 400, "AAAA-MM-DD");
    }

    /** Cenário "Aeroporto inexistente". */
    @Test
    void returns404ForUnknownAirport() {
        problem(get(port, "/flights?airport=ZZZZ"), 404, "ZZZZ");
    }

    /** Cenário "Método não suportado". */
    @Test
    void rejectsPost() {
        HttpResponse<String> response = send("POST", port, "/flights?airport=SBGR");
        assertThat(response.statusCode()).isEqualTo(405);
    }

    /** PostgreSQL indisponível resulta em 503 sem detalhes da conexão. */
    @Test
    void returns503WhenDatabaseFails() {
        willThrow(new DataAccessResourceFailureException("conexão recusada em 127.0.0.1"))
                .given(repository).count(any());
        HttpResponse<String> response = get(port, "/flights?airport=SBGR");
        problem(response, 503, "PostgreSQL indisponível");
        assertThat(response.body()).doesNotContain("127.0.0.1");
    }

    private JsonNode ok(String path) {
        HttpResponse<String> response = get(port, path);
        assertThat(response.statusCode()).as("HTTP de %s: %s", path, response.body()).isEqualTo(200);
        return json(response);
    }

    private static void problem(HttpResponse<String> response, int status, String detail) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(json(response).path("detail").asString()).contains(detail);
    }

    private static List<String> numbers(JsonNode body) {
        return body.path("content").valueStream().map(f -> f.path("flightNumber").asString()).toList();
    }

    private static JsonNode byNumber(JsonNode content, String number) {
        return content.valueStream().filter(f -> f.path("flightNumber").asString().equals(number)).findFirst()
                .orElseThrow();
    }

    private void flight(long batch, String airline, String number, String origin, String destination,
            String scheduled, String actual, String status) {
        jdbc.sql("""
                INSERT INTO flight (airline_icao, flight_number, authorization_code, line_type, origin_icao,
                    destination_icao, scheduled_departure, actual_departure, scheduled_arrival, actual_arrival,
                    status, justification_code, batch_id)
                VALUES (:airline, :number, '0', 'N', :origin, :destination, CAST(:scheduled AS timestamp),
                    CAST(:actual AS timestamp), CAST(:scheduled AS timestamp) + interval '1 hour',
                    CAST(:actual AS timestamp) + interval '1 hour', :status, 'N/A', :batch)
                """)
                .param("airline", airline).param("number", number).param("origin", origin)
                .param("destination", destination).param("scheduled", scheduled).param("actual", actual)
                .param("status", status).param("batch", batch).update();
    }
}
