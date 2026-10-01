package br.com.skyledger.ingestion;

import static br.com.skyledger.ingestion.IngestionEndpointTest.assertNothingWritten;
import static br.com.skyledger.ingestion.IngestionEndpointTest.assertProblem;
import static br.com.skyledger.ingestion.IngestionEndpointTest.truncateAll;
import static br.com.skyledger.support.LocalHttp.send;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Cenários de fonte do {@code anac-data} não configurada ou inexistente, cada um
 * com o próprio valor de {@code skyledger.anac-data.path}.
 */
class IngestionSourceUnavailableTest {

    /** Cenário "Caminho não configurado". */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "skyledger.anac-data.path=")
    class NotConfigured {

        @LocalServerPort
        private int port;

        @Autowired
        private JdbcClient jdbc;

        @BeforeEach
        void reset() {
            truncateAll(jdbc);
        }

        @Test
        void returns503CitingProperty() {
            assertProblem(send("POST", port, IngestionEndpointTest.PATH), 503, "skyledger.anac-data.path");
            assertNothingWritten(jdbc);
        }
    }

    /** Cenário "Caminho inexistente". */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class Nonexistent {

        @TempDir
        static Path parent;

        @LocalServerPort
        private int port;

        @Autowired
        private JdbcClient jdbc;

        @DynamicPropertySource
        static void missingPath(DynamicPropertyRegistry registry) {
            registry.add("skyledger.anac-data.path", () -> parent.resolve("nao-existe").toString());
        }

        @BeforeEach
        void reset() {
            truncateAll(jdbc);
        }

        @Test
        void returns503CitingPath() {
            assertProblem(send("POST", port, IngestionEndpointTest.PATH), 503,
                    parent.resolve("nao-existe").toString());
            assertNothingWritten(jdbc);
        }
    }
}
