package br.com.skyledger.flight;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Converte as falhas da consulta de voos em respostas {@code application/problem+json}.
 */
@RestControllerAdvice(assignableTypes = FlightQueryController.class)
public class FlightQueryExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(FlightQueryExceptionHandler.class);

    /**
     * Parâmetro inválido.
     *
     * @param ex exceção com o parâmetro e o motivo
     * @return problema com status 400
     */
    @ExceptionHandler(InvalidQueryParameterException.class)
    public ProblemDetail invalidParameter(InvalidQueryParameterException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Parâmetro inválido");
        problem.setProperty("parameter", ex.parameter());
        return problem;
    }

    /**
     * Aeroporto inexistente.
     *
     * @param ex exceção com o código
     * @return problema com status 404
     */
    @ExceptionHandler(AirportNotFoundException.class)
    public ProblemDetail airportNotFound(AirportNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Aeroporto não encontrado");
        return problem;
    }

    /**
     * PostgreSQL indisponível.
     *
     * @param ex falha de acesso ao banco
     * @return problema com status 503, sem detalhes da conexão
     */
    @ExceptionHandler({CannotGetJdbcConnectionException.class, DataAccessResourceFailureException.class})
    public ProblemDetail databaseUnavailable(RuntimeException ex) {
        LOG.warn("Consulta de voos interrompida por falha de banco: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "PostgreSQL indisponível; tente novamente");
        problem.setTitle("Banco de dados indisponível");
        return problem;
    }
}
