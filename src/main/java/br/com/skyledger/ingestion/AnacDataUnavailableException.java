package br.com.skyledger.ingestion;

/**
 * A fonte de dados da ANAC não pode ser usada: caminho não configurado,
 * inexistente ou sem os arquivos esperados.
 */
public class AnacDataUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Cria a exceção com o motivo.
     *
     * @param message motivo, citando a propriedade ou o caminho envolvido
     */
    public AnacDataUnavailableException(String message) {
        super(message);
    }
}
