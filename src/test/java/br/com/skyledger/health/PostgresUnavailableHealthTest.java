package br.com.skyledger.health;

import static br.com.skyledger.support.LocalHttp.get;
import static br.com.skyledger.support.LocalHttp.json;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Cenário "PostgreSQL indisponível": banco apontado para uma porta sem serviço.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.docker.compose.enabled=false",
            // Testa o health com o banco caindo depois da subida: sem migração na inicialização.
            "spring.flyway.enabled=false",
            "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/skyledger"
        })
class PostgresUnavailableHealthTest {

    @LocalServerPort
    private int port;

    /**
     * Senha aleatória por execução: não há credencial fixa no teste.
     *
     * @param registry registro de propriedades do contexto de teste
     */
    @DynamicPropertySource
    static void randomPassword(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.password", () -> "pw-" + UUID.randomUUID());
    }

    /** Banco fora resulta em {@code DOWN}/503, respondido dentro do timeout de conexão. */
    @Test
    void reportsDownWhenPostgresIsUnavailable() {
        long start = System.nanoTime();
        HttpResponse<String> response = get(port, "/actuator/health");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        JsonNode body = json(response);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(body.path("status").asString()).isEqualTo("DOWN");
        assertThat(body.path("components").path("db").path("status").asString()).isEqualTo("DOWN");
        assertThat(elapsed).isLessThan(Duration.ofSeconds(10));
    }
}
