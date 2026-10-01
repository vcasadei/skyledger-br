package br.com.skyledger.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Rollback da V1 ({@code db/rollback/V1__down.sql}) num schema descartável.
 */
@SpringBootTest
class RollbackMigrationTest {

    private static final String SCHEMA = "skyledger_rollback_test";

    private static final List<String> V1_TABLES =
            List.of("airline", "airport", "flight", "ingestion_batch", "ingestion_rejection");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbc;

    /** Remove o schema descartável. */
    @AfterEach
    void dropSchema() {
        jdbc.sql("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE").update();
    }

    /**
     * {@code migrate}, down e novo {@code migrate}: o down remove as tabelas e o
     * registro da versão, e a V1 volta a ser aplicada.
     *
     * @throws Exception se o script de rollback falhar
     */
    @Test
    void downScriptRestoresPriorShapeAndAllowsReapplying() throws Exception {
        Flyway flyway = Flyway.configure().dataSource(dataSource).schemas(SCHEMA).defaultSchema(SCHEMA)
                .createSchemas(true).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(tables()).containsAll(V1_TABLES);

        // setSchema pela API JDBC, e não SET search_path: o Hikari rastreia a troca e
        // devolve a conexão ao pool com o schema configurado (skyledger_test).
        try (Connection connection = dataSource.getConnection()) {
            connection.setSchema(SCHEMA);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/rollback/V1__down.sql"));
        }
        assertThat(tables()).doesNotContainAnyElementsOf(V1_TABLES);

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(tables()).containsAll(V1_TABLES);
    }

    private List<String> tables() {
        return jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = :schema")
                .param("schema", SCHEMA).query(String.class).list();
    }
}
