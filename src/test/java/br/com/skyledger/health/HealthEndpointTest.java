package br.com.skyledger.health;

import static br.com.skyledger.support.LocalHttp.get;
import static br.com.skyledger.support.LocalHttp.json;
import static br.com.skyledger.support.LocalHttp.send;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

/**
 * Contrato do health-check com PostgreSQL e Redis do {@code compose.yaml} de pé.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointTest {

    @LocalServerPort
    private int port;

    /** Cenário "Todas as dependências disponíveis". */
    @Test
    void reportsUpWhenAllDependenciesAreAvailable() {
        HttpResponse<String> response = get(port, "/actuator/health");
        JsonNode body = json(response);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body.path("status").asString()).isEqualTo("UP");
        assertThat(body.path("components").path("db").path("status").asString()).isEqualTo("UP");
        assertThat(body.path("components").path("redis").path("status").asString()).isEqualTo("UP");
    }

    /** Cenário "Resposta sem detalhes sensíveis". */
    @Test
    void doesNotExposeDependencyDetails() {
        String body = get(port, "/actuator/health").body();

        assertThat(body).doesNotContain("localhost", "127.0.0.1", "5432", "6379", "skyledger", "version",
                "details", "PostgreSQL", "groups");
    }

    /** Cenário "Outros endpoints do Actuator não expostos". */
    @Test
    void doesNotExposeOtherActuatorEndpoints() {
        assertThat(get(port, "/actuator/env").statusCode()).isEqualTo(404);
        assertThat(get(port, "/actuator/beans").statusCode()).isEqualTo(404);
        assertThat(get(port, "/actuator/health/liveness").statusCode()).isEqualTo(404);
        assertThat(get(port, "/actuator").statusCode()).isEqualTo(404);
    }

    /** Cenário "Método não suportado". */
    @Test
    void rejectsPost() {
        assertThat(send("POST", port, "/actuator/health").statusCode()).isEqualTo(405);
    }
}
