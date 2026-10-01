package br.com.skyledger.ingestion.parser;

/**
 * O arquivo inteiro está fora do layout esperado (metadado ou cabeçalho) e não
 * pode ser processado.
 */
public class InvalidSourceFileException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Cria a exceção com o motivo.
     *
     * @param message motivo, citando a linha e o elemento do layout
     */
    public InvalidSourceFileException(String message) {
        super(message);
    }
}
