package br.com.skyledger.ingestion;

import br.com.skyledger.ingestion.IngestionReport.FileReport;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;

/**
 * Executa a ingestão do {@code anac-data}: cadastro de aeródromos e depois os
 * arquivos do VRA em ordem cronológica, um por transação.
 */
@Service
public class IngestionService {

    private final AnacDataSource source;
    private final FileIngestor fileIngestor;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Cria o serviço.
     *
     * @param source       localização dos arquivos
     * @param fileIngestor processamento de cada arquivo
     */
    public IngestionService(AnacDataSource source, FileIngestor fileIngestor) {
        this.source = source;
        this.fileIngestor = fileIngestor;
    }

    /**
     * Ingere todos os arquivos da fonte configurada.
     *
     * @return relatório por arquivo
     * @throws IngestionAlreadyRunningException se já houver uma ingestão em andamento
     * @throws AnacDataUnavailableException     se a fonte estiver inutilizável (nada é gravado)
     */
    public IngestionReport ingest() {
        if (!lock.tryLock()) {
            throw new IngestionAlreadyRunningException();
        }
        try {
            AnacDataSource.SourceFiles files = source.resolve();
            OffsetDateTime startedAt = OffsetDateTime.now();
            List<FileReport> reports = new ArrayList<>();
            reports.add(fileIngestor.ingestAerodromes(files.aerodromes()));
            for (AnacDataSource.VraFile vra : files.vra()) {
                reports.add(fileIngestor.ingestVra(vra.path()));
            }
            return new IngestionReport(startedAt, OffsetDateTime.now(), List.copyOf(reports));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Trava de concorrência, exposta ao pacote para os testes simularem uma
     * ingestão em andamento.
     *
     * @return trava da ingestão
     */
    ReentrantLock lock() {
        return lock;
    }
}
