package br.com.skyledger.ingestion;

/**
 * Já existe uma ingestão em andamento nesta instância.
 */
public class IngestionAlreadyRunningException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Cria a exceção com a mensagem padrão. */
    public IngestionAlreadyRunningException() {
        super("Já existe uma ingestão em andamento");
    }
}
