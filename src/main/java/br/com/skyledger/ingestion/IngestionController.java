package br.com.skyledger.ingestion;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint que dispara a ingestão dos dados da ANAC.
 */
@RestController
@RequestMapping("/api/ingestions")
@Tag(name = "Ingestão", description = "Carga dos arquivos do VRA e do cadastro de Aeródromos Públicos")
public class IngestionController {

    private final IngestionService service;

    /**
     * Cria o controller.
     *
     * @param service serviço de ingestão
     */
    public IngestionController(IngestionService service) {
        this.service = service;
    }

    /**
     * Ingere o cadastro de aeródromos e depois os arquivos do VRA da raiz configurada.
     *
     * @return relatório por arquivo
     */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Ingere os arquivos do anac-data",
            description = "Processa AerodromosPublicos.csv e depois cada VRA_<AAAA><M>.csv em ordem cronológica, "
                    + "um por transação. Idempotente: reprocessar não duplica registros (contados em duplicates).")
    @ApiResponse(responseCode = "200", description = "Ingestão executada; relatório por arquivo",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = IngestionReport.class)))
    @ApiResponse(responseCode = "409", description = "Já existe uma ingestão em andamento",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503",
            description = "Fonte do anac-data inutilizável (não configurada, inexistente ou sem arquivos) "
                    + "ou PostgreSQL indisponível; nada é gravado",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public IngestionReport ingest() {
        return service.ingest();
    }
}
