package br.com.skyledger.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willThrow;

import br.com.skyledger.ingestion.IngestionReport.FileReport;
import br.com.skyledger.ingestion.IngestionReport.FileStatus;
import br.com.skyledger.ingestion.persistence.FlightRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Ingestão das fixtures pelo serviço, contra o schema de testes, com a raiz do
 * {@code anac-data} num diretório temporário.
 *
 * <p>Fixtures: o cadastro tem 6 aeródromos reais (inclui {@code SBMI}, com altitude
 * {@code ,0}); o {@code VRA_20265} tem 8 linhas (uma duplicata exata, um
 * cancelado, um realizado sem previsto, o par {@code ACA 0091} com partidas reais
 * diferentes, um voo de 1º de junho e uma linha inválida); o {@code VRA_20266}
 * repete o voo de 1º de junho e traz um voo novo.
 */
@SpringBootTest
class IngestionServiceTest {

    @TempDir
    static Path root;

    @Autowired
    private IngestionService service;

    @Autowired
    private JdbcClient jdbc;

    @MockitoSpyBean
    private FlightRepository flightRepository;

    private AnacDataFixture fixture;

    @DynamicPropertySource
    static void anacDataPath(DynamicPropertyRegistry registry) {
        registry.add("skyledger.anac-data.path", () -> root.toString());
    }

    @BeforeEach
    void reset() {
        IngestionEndpointTest.truncateAll(jdbc);
        fixture = new AnacDataFixture(root);
        fixture.clear();
        fixture.copyAll();
    }

    /** Contagens da primeira ingestão, incluindo duplicata exata e rejeição. */
    @Test
    void ingestsFixturesWithExactCounts() {
        List<FileReport> files = service.ingest().files();

        assertThat(files).extracting(FileReport::file)
                .containsExactly("AerodromosPublicos.csv", "VRA_20265.csv", "VRA_20266.csv");
        assertThat(files).extracting(FileReport::status).containsOnly(FileStatus.COMPLETED);
        assertCounts(files.get(0), 6, 6, 0, 0, 0);
        assertCounts(files.get(1), 8, 6, 0, 1, 1);
        assertCounts(files.get(2), 2, 1, 0, 1, 0);
        assertThat(files.get(1).sourceUpdatedAt()).isEqualTo(LocalDate.of(2026, 8, 28));
        assertThat(files.get(1).rejections()).singleElement().satisfies(rejection -> {
            assertThat(rejection.line()).isEqualTo(10);
            assertThat(rejection.reason()).contains("CANCELADO", "Partida Real");
        });
        assertThat(count("flight")).isEqualTo(7);
    }

    /** Cenário "Reprocessamento do mesmo arquivo". */
    @Test
    void reprocessingInsertsNothing() {
        service.ingest();
        List<FileReport> second = service.ingest().files();

        assertThat(second.subList(1, 3)).allSatisfy(file -> {
            assertThat(file.inserted()).isZero();
            assertThat(file.duplicates()).isEqualTo(file.rowsRead() - file.rejected());
        });
        assertThat(count("flight")).isEqualTo(7);
    }

    /** Cenários "Linha duplicada dentro do arquivo" e "Mesmo voo em dois meses". */
    @Test
    void duplicatesWithinFileAndAcrossMonthsAreStoredOnce() {
        service.ingest();

        assertThat(flightCount("AZU", "4840")).isEqualTo(1);
        assertThat(flightCount("AZU", "4000")).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT b.file_name FROM flight f JOIN ingestion_batch b ON b.id = f.batch_id
                WHERE f.airline_icao = 'AZU' AND f.flight_number = '4000'
                """).query(String.class).single()).isEqualTo("VRA_20265.csv");
    }

    /** Cenário "Mesmo voo previsto com partidas reais diferentes". */
    @Test
    void sameScheduledFlightWithDifferentActualDeparturesIsTwoFlights() {
        service.ingest();
        assertThat(flightCount("ACA", "0091")).isEqualTo(2);
    }

    /** Cenário "Cancelado reprocessado", e os valores gravados de cancelado e realizado sem previsto. */
    @Test
    void storesNullTimesAndDoesNotDuplicateCancelledOnReprocessing() {
        service.ingest();
        service.ingest();

        Map<String, Object> cancelled = flight("GLO", "1015");
        assertThat(cancelled.get("status")).isEqualTo("CANCELADO");
        assertThat(cancelled.get("actual_departure")).isNull();
        assertThat(cancelled.get("scheduled_departure")).isNotNull();
        assertThat(flightCount("GLO", "1015")).isEqualTo(1);

        Map<String, Object> noSchedule = flight("GLO", "1016");
        assertThat(noSchedule.get("status")).isEqualTo("REALIZADO");
        assertThat(noSchedule.get("scheduled_departure")).isNull();
        assertThat(noSchedule.get("actual_departure")).isNotNull();
        assertThat(flightCount("GLO", "1016")).isEqualTo(1);

        assertThat(flight("AZU", "4000").get("scheduled_departure").toString()).isEqualTo("2026-06-01 00:30:00.0");
    }

    /** Cenários "Aeroporto estrangeiro" e "Companhia nova". */
    @Test
    void createsCodeOnlyAirportsAndAirlinesOnce() {
        service.ingest();

        Map<String, Object> foreign = jdbc.sql("SELECT * FROM airport WHERE icao = 'CYYZ'").query().singleRow();
        assertThat(foreign.get("name")).isNull();
        assertThat(foreign.get("latitude")).isNull();
        assertThat(foreign.get("in_public_registry")).isEqualTo(false);
        assertThat(jdbc.sql("SELECT icao FROM airline ORDER BY icao").query(String.class).list())
                .containsExactly("ACA", "AZU", "GLO");
        assertThat(count("airport")).isEqualTo(8);
    }

    /** Cenário "Aeródromo cadastrado" e "Altitude sem parte inteira", com valores exatos. */
    @Test
    void storesRegistryDataExactly() {
        service.ingest();

        Map<String, Object> sbrb = jdbc.sql("SELECT * FROM airport WHERE icao = 'SBRB'").query().singleRow();
        assertThat(sbrb.get("name")).isEqualTo("Plácido de Castro");
        assertThat(sbrb.get("uf")).isEqualTo("Acre");
        assertThat(sbrb.get("latitude").toString()).isEqualTo("-9.868333300");
        assertThat(sbrb.get("altitude_m").toString()).isEqualTo("193.0");
        assertThat(sbrb.get("in_public_registry")).isEqualTo(true);
        assertThat(jdbc.sql("SELECT altitude_m FROM airport WHERE icao = 'SBMI'").query().singleRow().get("altitude_m"))
                .isNull();
    }

    /** Cenário "Aeroporto criado pelo VRA e enriquecido depois" e "Reprocessamento do cadastro". */
    @Test
    void enrichesVraAirportAndReprocessesRegistryWithoutDuplicates() {
        service.ingest();
        assertThat(jdbc.sql("SELECT name FROM airport WHERE icao = 'SBGR'").query().singleRow().get("name")).isNull();

        String registry = new String(readFixture("fixtures/aerodromos/AerodromosPublicos.csv"),
                StandardCharsets.ISO_8859_1);
        fixture.clear();
        fixture.copyAll();
        fixture.write(AnacDataFixture.AERODROMES, registry + "SBGR;SP0002;Guarulhos;GUARULHOS;São Paulo;Guarulhos;"
                + "São Paulo;-23.435556;-46.473056;23°26'08\"S;046°28'23\"W;750,0;;;Cadastrado;;;\r\n",
                StandardCharsets.ISO_8859_1);
        FileReport aerodromes = service.ingest().files().get(0);

        assertCounts(aerodromes, 7, 0, 7, 0, 0);
        assertThat(jdbc.sql("SELECT name FROM airport WHERE icao = 'SBGR'").query(String.class).single())
                .isEqualTo("Guarulhos");
        assertThat(count("airport")).isEqualTo(8);
    }

    /** Cenários "Histórico de reprocessamentos" e "Voo rastreável até o arquivo", com rejeição persistida. */
    @Test
    void keepsBatchHistoryAndTraceability() {
        service.ingest();
        service.ingest();

        List<Map<String, Object>> batches = jdbc.sql("""
                SELECT sha256, inserted FROM ingestion_batch WHERE file_name = 'VRA_20265.csv' ORDER BY id
                """).query().listOfRows();
        assertThat(batches).hasSize(2);
        assertThat(batches.get(0).get("sha256")).isEqualTo(batches.get(1).get("sha256"));
        assertThat(batches.get(1).get("inserted")).isEqualTo(0);

        Map<String, Object> origin = jdbc.sql("""
                SELECT b.file_name, b.source_updated_at FROM flight f JOIN ingestion_batch b ON b.id = f.batch_id
                WHERE f.airline_icao = 'AZU' AND f.flight_number = '4841'
                """).query().singleRow();
        assertThat(origin.get("file_name")).isEqualTo("VRA_20266.csv");
        assertThat(origin.get("source_updated_at").toString()).isEqualTo("2026-08-28");

        assertThat(jdbc.sql("SELECT line_number, reason FROM ingestion_rejection").query().listOfRows())
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.get("line_number")).isEqualTo(10));
    }

    /** Arquivo com layout inválido é rejeitado inteiro e os demais seguem. */
    @Test
    void rejectsInvalidFileAndContinues() {
        fixture.write(AnacDataFixture.VRA_DIR + "VRA_20264.csv", "sem metadado\r\n", StandardCharsets.UTF_8);

        List<FileReport> files = service.ingest().files();

        assertThat(files.get(1).file()).isEqualTo("VRA_20264.csv");
        assertThat(files.get(1).status()).isEqualTo(FileStatus.REJECTED);
        assertThat(files.get(1).error()).contains("Linha 1");
        assertThat(files.get(2).status()).isEqualTo(FileStatus.COMPLETED);
        assertThat(jdbc.sql("SELECT status FROM ingestion_batch WHERE file_name = 'VRA_20264.csv'")
                .query(String.class).single()).isEqualTo("REJECTED");
    }

    /** Cenário "Falha de banco no meio do arquivo": nenhum voo do arquivo fica gravado. */
    @Test
    void rollsBackWholeFileOnDatabaseFailure() {
        willThrow(new DataAccessResourceFailureException("banco indisponível"))
                .given(flightRepository).insertNewFlights(anyLong());

        assertThatThrownBy(service::ingest).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(count("flight")).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM ingestion_batch WHERE dataset = 'VRA'").query(Integer.class)
                .single()).isZero();
    }

    private static void assertCounts(FileReport file, int rowsRead, int inserted, int updated, int duplicates,
            int rejected) {
        assertThat(file.rowsRead()).as("rowsRead de %s", file.file()).isEqualTo(rowsRead);
        assertThat(file.inserted()).as("inserted de %s", file.file()).isEqualTo(inserted);
        assertThat(file.updated()).as("updated de %s", file.file()).isEqualTo(updated);
        assertThat(file.duplicates()).as("duplicates de %s", file.file()).isEqualTo(duplicates);
        assertThat(file.rejected()).as("rejected de %s", file.file()).isEqualTo(rejected);
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    private int flightCount(String airline, String number) {
        return jdbc.sql("SELECT count(*) FROM flight WHERE airline_icao = :airline AND flight_number = :number")
                .param("airline", airline).param("number", number).query(Integer.class).single();
    }

    private Map<String, Object> flight(String airline, String number) {
        return jdbc.sql("SELECT * FROM flight WHERE airline_icao = :airline AND flight_number = :number")
                .param("airline", airline).param("number", number).query().singleRow();
    }

    private static byte[] readFixture(String resource) {
        try (InputStream input = new ClassPathResource(resource).getInputStream()) {
            return input.readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
