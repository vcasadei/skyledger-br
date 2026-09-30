package br.com.skyledger.config;

import static br.com.skyledger.support.LocalHttp.get;
import static br.com.skyledger.support.LocalHttp.json;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

/**
 * Documentação OpenAPI gerada pela aplicação.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiDocumentationTest {

    @LocalServerPort
    private int port;

    /** Cenário "Health-check documentado". */
    @Test
    void documentsHealthCheckWithSuccessAndErrorResponses() {
        HttpResponse<String> response = get(port, "/v3/api-docs");
        JsonNode spec = json(response);
        JsonNode responses = spec.path("paths").path("/actuator/health").path("get").path("responses");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(spec.path("openapi").asString()).startsWith("3.");
        assertThat(responses.has("200")).isTrue();
        assertThat(responses.has("503")).isTrue();
        assertThat(spec.path("components").path("schemas").has("HealthResponse")).isTrue();
    }

    /** Só o health-check do Actuator entra na especificação. */
    @Test
    void documentsOnlyTheExposedActuatorEndpoint() {
        JsonNode paths = json(get(port, "/v3/api-docs")).path("paths");

        assertThat(paths.propertyNames()).filteredOn(path -> path.startsWith("/actuator"))
                .containsExactly("/actuator/health");
    }

    /** Parte automatizável do cenário "Interface da documentação". */
    @Test
    void servesSwaggerUi() {
        HttpResponse<String> redirect = get(port, "/swagger-ui.html");
        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThat(redirect.headers().firstValue("Location")).hasValueSatisfying(
                location -> assertThat(location).endsWith("/swagger-ui/index.html"));

        HttpResponse<String> page = get(port, "/swagger-ui/index.html");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("swagger-ui");
    }
}
