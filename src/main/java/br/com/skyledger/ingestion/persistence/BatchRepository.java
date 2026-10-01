package br.com.skyledger.ingestion.persistence;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Registro de lotes de ingestão (um por arquivo processado) e de linhas rejeitadas.
 */
@Repository
public class BatchRepository {

    private final JdbcTemplate jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc acesso JDBC ligado à transação corrente
     */
    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Grava um lote e devolve seu identificador.
     *
     * @param batch dados do lote
     * @return identificador gerado
     */
    public long insert(BatchRow batch) {
        Long id = jdbc.queryForObject("""
                INSERT INTO ingestion_batch (dataset, file_name, sha256, source_updated_at, status, rows_read,
                    inserted, updated, duplicates, rejected, error, started_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, batch.dataset(), batch.fileName(), batch.sha256(), batch.sourceUpdatedAt(),
                batch.status(), batch.rowsRead(), batch.inserted(), batch.updated(), batch.duplicates(),
                batch.rejected(), batch.error(), Timestamp.from(batch.startedAt().toInstant()),
                Timestamp.from(batch.finishedAt().toInstant()));
        if (id == null) {
            throw new IllegalStateException("INSERT em ingestion_batch não devolveu id");
        }
        return id;
    }

    /**
     * Atualiza as contagens e o fim de um lote já gravado.
     *
     * @param id    identificador do lote
     * @param batch dados finais do lote
     */
    public void complete(long id, BatchRow batch) {
        jdbc.update("""
                UPDATE ingestion_batch SET rows_read = ?, inserted = ?, updated = ?, duplicates = ?, rejected = ?,
                    finished_at = ?
                WHERE id = ?
                """, batch.rowsRead(), batch.inserted(), batch.updated(), batch.duplicates(), batch.rejected(),
                Timestamp.from(batch.finishedAt().toInstant()), id);
    }

    /**
     * Grava as linhas rejeitadas de um lote.
     *
     * @param batchId    lote ao qual pertencem
     * @param rejections linhas rejeitadas
     */
    public void insertRejections(long batchId, List<RejectionRow> rejections) {
        jdbc.batchUpdate("INSERT INTO ingestion_rejection (batch_id, line_number, reason) VALUES (?, ?, ?)",
                rejections.stream().map(r -> new Object[] {batchId, r.line(), r.reason()}).toList());
    }

    /**
     * Dados de um lote.
     *
     * @param dataset         {@code AERODROMOS} ou {@code VRA}
     * @param fileName        nome do arquivo
     * @param sha256          hash do conteúdo
     * @param sourceUpdatedAt data de "Atualizado em", ou {@code null}
     * @param status          {@code COMPLETED} ou {@code REJECTED}
     * @param rowsRead        linhas de dados lidas
     * @param inserted        registros novos
     * @param updated         registros atualizados
     * @param duplicates      linhas já existentes
     * @param rejected        linhas rejeitadas
     * @param error           motivo da rejeição do arquivo, ou {@code null}
     * @param startedAt       início do processamento
     * @param finishedAt      fim do processamento
     */
    public record BatchRow(String dataset, String fileName, String sha256, LocalDate sourceUpdatedAt,
            String status, int rowsRead, int inserted, int updated, int duplicates, int rejected, String error,
            OffsetDateTime startedAt, OffsetDateTime finishedAt) {
    }

    /**
     * Linha rejeitada.
     *
     * @param line   número da linha física
     * @param reason motivo
     */
    public record RejectionRow(int line, String reason) {
    }
}
