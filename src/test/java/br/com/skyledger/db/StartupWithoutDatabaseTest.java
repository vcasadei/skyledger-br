package br.com.skyledger.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.skyledger.SkyledgerApplication;
import java.util.UUID;
import java.net.ConnectException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

/**
 * Cenário "PostgreSQL fora na inicialização e depois recuperado", parte
 * automatizável: com o banco indisponível, as migrações falham e a aplicação não sobe.
 */
class StartupWithoutDatabaseTest {

    /** A inicialização falha na migração em vez de subir sem esquema. */
    @Test
    void failsStartupWhenPostgresIsUnavailable() {
        assertThatThrownBy(() -> SpringApplication.run(SkyledgerApplication.class,
                "--spring.docker.compose.enabled=false",
                "--server.port=0",
                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/skyledger",
                "--spring.datasource.password=pw-" + UUID.randomUUID()))
                .hasRootCauseInstanceOf(ConnectException.class)
                .satisfies(ex -> assertThat(ex).hasStackTraceContaining("flywayInitializer")
                        .hasStackTraceContaining("127.0.0.1:1"));
    }
}
