package br.com.skyledger.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Operações de limpeza do banco de testes.
 */
public final class TestDatabase {

    private TestDatabase() {
    }

    /**
     * Esvazia as tabelas da ingestão no schema de testes.
     *
     * <p>Proteção: falha se a conexão não estiver no schema {@code skyledger_test},
     * para nunca truncar o schema {@code public} de desenvolvimento.
     *
     * @param jdbc acesso JDBC do contexto de teste
     */
    public static void truncateAll(JdbcClient jdbc) {
        assertThat(jdbc.sql("SELECT current_schema()").query(String.class).single())
                .as("schema da conexão de teste").isEqualTo("skyledger_test");
        jdbc.sql("TRUNCATE flight, ingestion_rejection, ingestion_batch, airport, airline RESTART IDENTITY CASCADE")
                .update();
    }
}
