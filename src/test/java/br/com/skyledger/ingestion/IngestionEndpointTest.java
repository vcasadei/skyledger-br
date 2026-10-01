package br.com.skyledger.ingestion;

import static br.com.skyledger.support.LocalHttp.json;
import static br.com.skyledger.support.LocalHttp.send;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willThrow;

import br.com.skyledger.ingestion.persistence.FlightRepository;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/**
 * Contrato HTTP de {@code POST /api/ingestions}, com a raiz do {@code anac-data}
 * num diretório temporário.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IngestionEndpointTest {

    static final String PATH = "/api/ingestions";

    @TempDir
    static Path root;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private IngestionService service;

    @MockitoSpyBean
    private FlightRepository flightRepository;

    private AnacDataFixture fixture;

    @DynamicPropertySource
    static void anacDataPath(DynamicPropertyRegistry registry) {
        registry.add("skyledger.anac-data.path", () -> root.toString());
    }

    @BeforeEach
    void reset() {
        truncateAll(jdbc);
        fixture = new AnacDataFixture(root);
        fixture.clear();
        fixture.copyAll();
    }

    /** Cenário "Ingestão completa dos arquivos do anac-data", com as fixtures. */
    @Test
    void ingestsAllFilesInOrder() {
        HttpResponse<String> response = post();
        JsonNode files = json(response).path("files");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        assertThat(files.valueStream().map(file -> file.path("file").asString()).toList())
                .containsExactly("AerodromosPublicos.csv", "VRA_20265.csv", "VRA_20266.csv");
        assertThat(files.valueStream().map(file -> file.path("status").asString()).toList())
                .containsOnly("COMPLETED");
        JsonNode may = files.get(1);
        assertThat(may.path("dataset").asString()).isEqualTo("VRA");
        assertThat(may.path("inserted").asInt()).isEqualTo(6);
        assertThat(may.path("duplicates").asInt()).isEqualTo(1);
        assertThat(may.path("rejected").asInt()).isEqualTo(1);
        assertThat(may.path("rejections").get(0).path("line").asInt()).isEqualTo(10);
        assertThat(may.path("sha256").asString()).hasSize(64);
        assertThat(may.path("sourceUpdatedAt").asString()).isEqualTo("2026-08-28");
        assertThat(json(response).path("startedAt").asString()).isNotBlank();
    }

    /** Cenário "Ordem cronológica com mês sem zero à esquerda". */
    @Test
    void ordersMonthsNumerically() {
        fixture.clear();
        fixture.copy("fixtures/aerodromos/AerodromosPublicos.csv", AnacDataFixture.AERODROMES);
        fixture.copy("fixtures/vra/VRA_20266.csv", AnacDataFixture.VRA_DIR + "VRA_202610.csv");
        fixture.copy("fixtures/vra/VRA_20265.csv", AnacDataFixture.VRA_DIR + "VRA_20269.csv");

        List<String> files = json(post()).path("files").valueStream().map(f -> f.path("file").asString()).toList();

        assertThat(files).containsExactly("AerodromosPublicos.csv", "VRA_20269.csv", "VRA_202610.csv");
    }

    /** Cenário "Fonte sem arquivos do VRA". */
    @Test
    void rejectsSourceWithoutVraFiles() {
        fixture.clear();
        fixture.copy("fixtures/aerodromos/AerodromosPublicos.csv", AnacDataFixture.AERODROMES);

        HttpResponse<String> response = post();

        assertProblem(response, 503, "Nenhum arquivo do VRA");
        assertNothingWritten(jdbc);
    }

    /** Cenário "Ingestão concorrente". */
    @Test
    void rejectsConcurrentIngestion() {
        service.lock().lock();
        try {
            assertProblem(post(), 409, "andamento");
        } finally {
            service.lock().unlock();
        }
        assertNothingWritten(jdbc);
    }

    /** Cenário "Método não suportado". */
    @Test
    void rejectsGet() {
        HttpResponse<String> response = send("GET", port, PATH);
        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
    }

    /** Cenário "Falha de banco no meio do arquivo", pelo endpoint. */
    @Test
    void returns503AndKeepsNoFlightsOnDatabaseFailure() {
        willThrow(new DataAccessResourceFailureException("banco indisponível"))
                .given(flightRepository).insertNewFlights(anyLong());

        HttpResponse<String> response = post();

        assertProblem(response, 503, "PostgreSQL indisponível");
        assertThat(response.body()).doesNotContain("banco indisponível");
        assertThat(jdbc.sql("SELECT count(*) FROM flight").query(Integer.class).single()).isZero();
    }

    private HttpResponse<String> post() {
        return send("POST", port, PATH);
    }

    static void assertProblem(HttpResponse<String> response, int status, String detail) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(json(response).path("status").asInt()).isEqualTo(status);
        assertThat(json(response).path("detail").asString()).contains(detail);
    }

    static void truncateAll(JdbcClient jdbc) {
        // Proteção: nunca truncar fora do schema de testes (o public é o de desenvolvimento).
        assertThat(jdbc.sql("SELECT current_schema()").query(String.class).single())
                .as("schema da conexão de teste").isEqualTo("skyledger_test");
        jdbc.sql("TRUNCATE flight, ingestion_rejection, ingestion_batch, airport, airline RESTART IDENTITY CASCADE")
                .update();
    }

    static void assertNothingWritten(JdbcClient jdbc) {
        for (String table : List.of("flight", "airport", "airline", "ingestion_batch", "ingestion_rejection")) {
            assertThat(jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single())
                    .as("linhas em %s", table).isZero();
        }
    }
}
