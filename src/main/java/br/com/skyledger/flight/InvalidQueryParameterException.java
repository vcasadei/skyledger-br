package br.com.skyledger.flight;

/**
 * Parâmetro da consulta de voos fora das regras.
 */
public class InvalidQueryParameterException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String parameter;

    /**
     * Cria a exceção.
     *
     * @param parameter nome do parâmetro inválido
     * @param message   motivo, citando o parâmetro
     */
    public InvalidQueryParameterException(String parameter, String message) {
        super(message);
        this.parameter = parameter;
    }

    /**
     * Parâmetro inválido.
     *
     * @return nome do parâmetro
     */
    public String parameter() {
        return parameter;
    }
}
