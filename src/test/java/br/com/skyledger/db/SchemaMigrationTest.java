package br.com.skyledger.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Esquema criado pela migração V1 na subida, no schema isolado de testes.
 */
@SpringBootTest
@Transactional
class SchemaMigrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private Flyway flyway;

    /** As conexões de teste usam o schema {@code skyledger_test}, nunca o {@code public} de dev. */
    @Test
    void testsRunInIsolatedSchema() {
        assertThat(jdbc.sql("SELECT current_schema()").query(String.class).single()).isEqualTo("skyledger_test");
    }

    /** As tabelas e constraints da V1 existem após a subida. */
    @Test
    void createsTablesAndConstraints() {
        assertThat(jdbc.sql("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'skyledger_test' AND table_type = 'BASE TABLE'
                """).query(String.class).list())
                .contains("airline", "airport", "flight", "ingestion_batch", "ingestion_rejection");
        assertThat(jdbc.sql("""
                SELECT constraint_name FROM information_schema.table_constraints
                WHERE table_schema = 'skyledger_test' AND table_name = 'flight'
                """).query(String.class).list())
                .contains("flight_natural_key", "flight_status_times");
    }

    /** Cenário "Primeira inicialização com banco vazio": V1 registrada e não reaplicada. */
    @Test
    void appliesMigrationsOnceAndRecordsHistory() {
        assertThat(jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    /** A CHECK de situação×horários rejeita um cancelado com partida real. */
    @Test
    void rejectsCancelledFlightWithActualDeparture() {
        long batch = insertReferenceData();
        assertThatThrownBy(() -> insertFlight(batch, "2026-05-01 10:00", "2026-05-01 10:05", "CANCELADO"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("flight_status_times");
    }

    /** A chave natural trata partida real ausente como igual entre linhas. */
    @Test
    void naturalKeyTreatsMissingActualDepartureAsEqual() {
        long batch = insertReferenceData();
        insertFlight(batch, "2026-05-01 10:00", null, "CANCELADO");
        assertThatThrownBy(() -> insertFlight(batch, "2026-05-01 10:00", null, "CANCELADO"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("flight_natural_key");
    }

    private long insertReferenceData() {
        jdbc.sql("INSERT INTO airline (icao) VALUES ('TST')").update();
        jdbc.sql("INSERT INTO airport (icao) VALUES ('SBAA'), ('SBBB')").update();
        return jdbc.sql("""
                INSERT INTO ingestion_batch (dataset, file_name, sha256, status, rows_read, inserted, updated,
                    duplicates, rejected, started_at, finished_at)
                VALUES ('VRA', 'teste.csv', repeat('0', 64), 'COMPLETED', 0, 0, 0, 0, 0, now(), now())
                RETURNING id
                """).query(Long.class).single();
    }

    private void insertFlight(long batch, String scheduled, String actual, String status) {
        jdbc.sql("""
                INSERT INTO flight (airline_icao, flight_number, authorization_code, line_type, origin_icao,
                    destination_icao, scheduled_departure, actual_departure, scheduled_arrival, actual_arrival,
                    status, justification_code, batch_id)
                VALUES ('TST', '0001', '0', 'N', 'SBAA', 'SBBB', CAST(:scheduled AS timestamp),
                    CAST(:actual AS timestamp), CAST(:scheduled AS timestamp) + interval '1 hour',
                    CAST(:actual AS timestamp) + interval '1 hour', :status, 'N/A', :batch)
                """)
                .param("scheduled", scheduled).param("actual", actual).param("status", status).param("batch", batch)
                .update();
    }
}
