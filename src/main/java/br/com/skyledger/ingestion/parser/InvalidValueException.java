package br.com.skyledger.ingestion.parser;

/**
 * Valor de campo fora das regras; vira o motivo da rejeição da linha.
 */
final class InvalidValueException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Cria a exceção com o motivo.
     *
     * @param message motivo, citando a coluna e o valor
     */
    InvalidValueException(String message) {
        super(message);
    }
}
