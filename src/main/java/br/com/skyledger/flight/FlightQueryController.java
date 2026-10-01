package br.com.skyledger.flight;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consulta somente leitura dos voos ingeridos por aeroporto.
 */
@RestController
@Tag(name = "Voos", description = "Consulta dos voos do VRA por aeroporto")
public class FlightQueryController {

    private final FlightQueryService service;

    /**
     * Cria o controller.
     *
     * @param service serviço de consulta
     */
    public FlightQueryController(FlightQueryService service) {
        this.service = service;
    }

    /**
     * Busca uma página de voos de um aeroporto.
     *
     * @param airport   código ICAO
     * @param direction sentido
     * @param from      data inicial inclusiva
     * @param to        data final inclusiva
     * @param status    situação
     * @param page      página
     * @param size      tamanho da página
     * @param sort      ordenação
     * @return página de voos
     */
    @GetMapping(path = "/flights", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Consulta voos por aeroporto",
            description = "Voos do VRA ingeridos, paginados e ordenáveis. O aeroporto é um código ICAO de 4 "
                    + "caracteres (ex.: SBGR para Guarulhos); códigos IATA (GRU) não são aceitos. Os horários são "
                    + "os publicados pela ANAC, sem fuso e sem conversão (ISO-8601 local, sem offset). Período e "
                    + "ordenação por partida usam a partida prevista, ou a real quando não houver prevista.")
    @ApiResponse(responseCode = "200", description = "Página de voos (content vazio além da última página)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = FlightPage.class)))
    @ApiResponse(responseCode = "400", description = "Parâmetro inválido; detail cita o parâmetro",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Aeroporto ICAO não existe na base",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "PostgreSQL indisponível",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public FlightPage find(
            @Parameter(description = "Código ICAO do aeroporto (4 caracteres A-Z0-9); IATA não é aceito",
                    required = true, example = "SBGR",
                    schema = @Schema(type = "string", pattern = "^[A-Z0-9]{4}$"))
            @RequestParam(required = false) String airport,
            @Parameter(description = "Sentido dos voos em relação ao aeroporto",
                    schema = @Schema(type = "string", allowableValues = {"DEPARTURE", "ARRIVAL", "BOTH"},
                            defaultValue = "BOTH"))
            @RequestParam(required = false) String direction,
            @Parameter(description = "Data inicial inclusiva (AAAA-MM-DD) da partida de referência",
                    example = "2026-05-01", schema = @Schema(type = "string", format = "date"))
            @RequestParam(required = false) String from,
            @Parameter(description = "Data final inclusiva (AAAA-MM-DD) da partida de referência",
                    example = "2026-05-31", schema = @Schema(type = "string", format = "date"))
            @RequestParam(required = false) String to,
            @Parameter(description = "Situação do voo; sem o parâmetro, todas",
                    schema = @Schema(type = "string", allowableValues = {"REALIZADO", "CANCELADO"}))
            @RequestParam(required = false) String status,
            @Parameter(description = "Página, a partir de 0",
                    schema = @Schema(type = "integer", minimum = "0", defaultValue = "0"))
            @RequestParam(required = false) String page,
            @Parameter(description = "Voos por página",
                    schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
            @RequestParam(required = false) String size,
            @Parameter(description = "Ordenação campo[,asc|desc]; campos: departure, arrival, airline, "
                    + "flightNumber, delay (sem atraso calculado sempre por último)",
                    example = "delay,desc", schema = @Schema(type = "string", defaultValue = "departure,asc",
                            allowableValues = {"departure", "departure,asc", "departure,desc", "arrival",
                                "arrival,asc", "arrival,desc", "airline", "airline,asc", "airline,desc",
                                "flightNumber", "flightNumber,asc", "flightNumber,desc", "delay", "delay,asc",
                                "delay,desc"}))
            @RequestParam(required = false) String sort) {
        return service.find(FlightQueryParameters.parse(airport, direction, from, to, status, page, size, sort));
    }
}
