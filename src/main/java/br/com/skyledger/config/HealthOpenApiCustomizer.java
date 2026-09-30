package br.com.skyledger.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

/**
 * Completa a documentação OpenAPI de {@code GET /actuator/health}.
 *
 * <p>O springdoc gera a operação do Actuator só com a resposta 200 e corpo sem
 * schema. Este customizer acrescenta a resposta 503 e o schema do corpo, para que
 * a especificação corresponda ao contrato do health-check.
 */
@Component
public class HealthOpenApiCustomizer implements OpenApiCustomizer {

    static final String HEALTH_PATH = "/actuator/health";

    static final String HEALTH_SCHEMA = "HealthResponse";

    /**
     * Ajusta a operação do health-check, se ela estiver na especificação.
     *
     * @param openApi especificação OpenAPI em construção
     */
    @Override
    public void customise(OpenAPI openApi) {
        PathItem path = openApi.getPaths() == null ? null : openApi.getPaths().get(HEALTH_PATH);
        if (path == null || path.getGet() == null) {
            return;
        }
        openApi.schema(HEALTH_SCHEMA, healthSchema());
        Operation operation = path.getGet();
        operation.summary("Health-check da aplicação")
                .description("Status geral e por componente. PostgreSQL (db) indisponível resulta em DOWN/503; "
                        + "Redis indisponível aparece como DEGRADED e mantém o status geral UP/200.")
                .responses(new ApiResponses()
                        .addApiResponse("200", response("Aplicação UP (Redis pode estar DEGRADED)"))
                        .addApiResponse("503", response("Aplicação DOWN: PostgreSQL indisponível")));
    }

    private static ApiResponse response(String description) {
        Schema<?> reference = new Schema<>().$ref("#/components/schemas/" + HEALTH_SCHEMA);
        MediaType mediaType = new MediaType().schema(reference);
        return new ApiResponse()
                .description(description)
                .content(new Content()
                        .addMediaType("application/vnd.spring-boot.actuator.v3+json", mediaType)
                        .addMediaType("application/json", mediaType));
    }

    private static Schema<?> healthSchema() {
        StringSchema status = new StringSchema();
        status.setEnum(List.of("UP", "DOWN", "DEGRADED", "OUT_OF_SERVICE", "UNKNOWN"));
        Schema<?> component = new ObjectSchema().addProperty("status", status);
        ObjectSchema health = new ObjectSchema();
        health.addProperty("status", status);
        health.addProperty("components", new ObjectSchema().additionalProperties(component));
        health.setRequired(List.of("status"));
        return health;
    }
}
