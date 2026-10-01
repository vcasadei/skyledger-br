package br.com.skyledger.ingestion;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Relatório de uma execução da ingestão: um item por arquivo, na ordem de processamento.
 *
 * @param startedAt  início da execução
 * @param finishedAt fim da execução
 * @param files      relatório de cada arquivo
 */
public record IngestionReport(OffsetDateTime startedAt, OffsetDateTime finishedAt, List<FileReport> files) {

    /** Conjunto de dados de um arquivo. */
    public enum Dataset {
        /** Cadastro de Aeródromos Públicos. */
        AERODROMOS,
        /** Voo Regular Ativo (VRA). */
        VRA
    }

    /** Resultado do processamento de um arquivo. */
    public enum FileStatus {
        /** Arquivo processado; linhas inválidas, se houver, foram rejeitadas individualmente. */
        COMPLETED,
        /** Arquivo inteiro rejeitado por metadado ou cabeçalho fora do layout. */
        REJECTED
    }

    /**
     * Relatório de um arquivo.
     *
     * @param dataset         conjunto de dados
     * @param file            nome do arquivo
     * @param status          resultado
     * @param sourceUpdatedAt data de "Atualizado em" da linha 1, ou {@code null}
     * @param sha256          hash SHA-256 do conteúdo
     * @param rowsRead        linhas de dados lidas
     * @param inserted        registros novos
     * @param updated         registros existentes atualizados (só aeródromos)
     * @param duplicates      linhas válidas que já existiam pela chave natural
     * @param rejected        linhas inválidas
     * @param rejections      até 100 linhas rejeitadas
     * @param error           motivo da rejeição do arquivo inteiro, ou {@code null}
     */
    public record FileReport(Dataset dataset, String file, FileStatus status, LocalDate sourceUpdatedAt,
            String sha256, int rowsRead, int inserted, int updated, int duplicates, int rejected,
            List<Rejection> rejections, String error) {
    }

    /**
     * Linha rejeitada.
     *
     * @param line   número da linha física no arquivo (a primeira linha de dados é a 3)
     * @param reason motivo
     */
    public record Rejection(int line, String reason) {
    }
}
