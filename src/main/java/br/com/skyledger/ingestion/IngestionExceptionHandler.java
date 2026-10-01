package br.com.skyledger.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Converte as falhas da ingestão em respostas {@code application/problem+json}.
 */
@RestControllerAdvice(assignableTypes = IngestionController.class)
public class IngestionExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(IngestionExceptionHandler.class);

    /**
     * Fonte de dados inutilizável.
     *
     * @param ex exceção com o motivo
     * @return problema com status 503
     */
    @ExceptionHandler(AnacDataUnavailableException.class)
    public ProblemDetail sourceUnavailable(AnacDataUnavailableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        problem.setTitle("Fonte de dados da ANAC indisponível");
        return problem;
    }

    /**
     * Ingestão concorrente.
     *
     * @param ex exceção de concorrência
     * @return problema com status 409
     */
    @ExceptionHandler(IngestionAlreadyRunningException.class)
    public ProblemDetail alreadyRunning(IngestionAlreadyRunningException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Ingestão em andamento");
        return problem;
    }

    /**
     * PostgreSQL indisponível; o arquivo em processamento é desfeito por inteiro.
     *
     * @param ex falha de acesso ao banco
     * @return problema com status 503, sem detalhes da conexão
     */
    @ExceptionHandler({CannotGetJdbcConnectionException.class, DataAccessResourceFailureException.class})
    public ProblemDetail databaseUnavailable(RuntimeException ex) {
        LOG.warn("Ingestão interrompida por falha de banco: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "PostgreSQL indisponível durante a ingestão; o arquivo em processamento foi desfeito");
        problem.setTitle("Banco de dados indisponível");
        return problem;
    }
}
