package br.com.skyledger.ingestion.parser;

/**
 * Resultado da leitura de uma linha de dados.
 *
 * @param <T> tipo do registro de uma linha válida
 */
public sealed interface ParsedLine<T> {

    /**
     * Número da linha física no arquivo (a primeira linha de dados é a 3).
     *
     * @return número da linha
     */
    int line();

    /**
     * Linha válida.
     *
     * @param line   número da linha física
     * @param record registro lido
     * @param <T>    tipo do registro
     */
    record Valid<T>(int line, T record) implements ParsedLine<T> {
    }

    /**
     * Linha rejeitada.
     *
     * @param line   número da linha física
     * @param reason motivo da rejeição
     * @param <T>    tipo do registro que a linha teria
     */
    record Rejected<T>(int line, String reason) implements ParsedLine<T> {
    }
}
