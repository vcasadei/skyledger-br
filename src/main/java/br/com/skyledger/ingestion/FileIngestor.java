package br.com.skyledger.ingestion;

import br.com.skyledger.ingestion.IngestionReport.Dataset;
import br.com.skyledger.ingestion.IngestionReport.FileReport;
import br.com.skyledger.ingestion.IngestionReport.FileStatus;
import br.com.skyledger.ingestion.IngestionReport.Rejection;
import br.com.skyledger.ingestion.parser.AerodromeFileParser;
import br.com.skyledger.ingestion.parser.AerodromeRecord;
import br.com.skyledger.ingestion.parser.InvalidSourceFileException;
import br.com.skyledger.ingestion.parser.ParsedLine;
import br.com.skyledger.ingestion.parser.VraFileParser;
import br.com.skyledger.ingestion.parser.VraRecord;
import br.com.skyledger.ingestion.persistence.AerodromeRepository;
import br.com.skyledger.ingestion.persistence.BatchRepository;
import br.com.skyledger.ingestion.persistence.BatchRepository.BatchRow;
import br.com.skyledger.ingestion.persistence.BatchRepository.RejectionRow;
import br.com.skyledger.ingestion.persistence.FlightRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Processa um arquivo de entrada numa única transação: valida, grava e registra o lote.
 */
@Component
public class FileIngestor {

    private static final Logger LOG = LoggerFactory.getLogger(FileIngestor.class);

    static final int STAGING_CHUNK = 1_000;

    static final int REPORTED_REJECTIONS = 100;

    private final VraFileParser vraParser;
    private final AerodromeFileParser aerodromeParser;
    private final FlightRepository flights;
    private final AerodromeRepository aerodromes;
    private final BatchRepository batches;
    private final TransactionTemplate transaction;

    /**
     * Cria o processador de arquivos.
     *
     * @param vraParser       leitor do VRA
     * @param aerodromeParser leitor do cadastro de aeródromos
     * @param flights         gravação de voos
     * @param aerodromes      gravação do cadastro
     * @param batches         registro de lotes e rejeições
     * @param transaction     transação por arquivo
     */
    public FileIngestor(VraFileParser vraParser, AerodromeFileParser aerodromeParser, FlightRepository flights,
            AerodromeRepository aerodromes, BatchRepository batches, TransactionTemplate transaction) {
        this.vraParser = vraParser;
        this.aerodromeParser = aerodromeParser;
        this.flights = flights;
        this.aerodromes = aerodromes;
        this.batches = batches;
        this.transaction = transaction;
    }

    /**
     * Ingere o cadastro de Aeródromos Públicos: cria ou atualiza aeroportos.
     *
     * @param path arquivo do cadastro
     * @return relatório do arquivo
     */
    public FileReport ingestAerodromes(Path path) {
        return ingest(Dataset.AERODROMOS, path, aerodromeParser::parse, new Target<AerodromeRecord>() {
            @Override
            public void prepare() {
                aerodromes.createStaging();
            }

            @Override
            public void stage(List<ParsedLine.Valid<AerodromeRecord>> lines) {
                aerodromes.stage(lines);
            }

            @Override
            public Written write(long batchId) {
                AerodromeRepository.UpsertResult result = aerodromes.upsert();
                return new Written(result.inserted(), result.updated());
            }
        });
    }

    /**
     * Ingere um arquivo mensal do VRA: cria companhias, aeroportos e voos novos.
     *
     * @param path arquivo do VRA
     * @return relatório do arquivo
     */
    public FileReport ingestVra(Path path) {
        return ingest(Dataset.VRA, path, vraParser::parse, new Target<VraRecord>() {
            @Override
            public void prepare() {
                flights.createStaging();
            }

            @Override
            public void stage(List<ParsedLine.Valid<VraRecord>> lines) {
                flights.stage(lines.stream().map(ParsedLine.Valid::record).toList());
            }

            @Override
            public Written write(long batchId) {
                flights.insertMissingAirlinesAndAirports();
                return new Written(flights.insertNewFlights(batchId), 0);
            }
        });
    }

    private <T> FileReport ingest(Dataset dataset, Path path, Parser<T> parser, Target<T> target) {
        FileReport report = transaction.execute(status -> ingestInTransaction(dataset, path, parser, target));
        LOG.info("Ingestão {} {}: {} lidas={} inseridas={} atualizadas={} duplicadas={} rejeitadas={}{}",
                dataset, report.file(), report.status(), report.rowsRead(), report.inserted(), report.updated(),
                report.duplicates(), report.rejected(), report.error() == null ? "" : " erro=" + report.error());
        return report;
    }

    private <T> FileReport ingestInTransaction(Dataset dataset, Path path, Parser<T> parser, Target<T> target) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        String fileName = path.getFileName().toString();
        String sha256 = sha256(path);
        Collector<T> collector = new Collector<>(target);
        LocalDate updatedAt;
        try (InputStream input = Files.newInputStream(path)) {
            target.prepare();
            updatedAt = parser.parse(input, collector);
            collector.flush();
        } catch (InvalidSourceFileException | IOException ex) {
            String error = ex instanceof IOException ? "Falha de leitura: " + ex.getMessage() : ex.getMessage();
            batches.insert(new BatchRow(dataset.name(), fileName, sha256, null, FileStatus.REJECTED.name(), 0, 0,
                    0, 0, 0, error, startedAt, OffsetDateTime.now()));
            return new FileReport(dataset, fileName, FileStatus.REJECTED, null, sha256, 0, 0, 0, 0, 0, List.of(),
                    error);
        }

        int rowsRead = collector.valid + collector.rejections.size();
        BatchRow batch = new BatchRow(dataset.name(), fileName, sha256, updatedAt, FileStatus.COMPLETED.name(),
                rowsRead, 0, 0, 0, collector.rejections.size(), null, startedAt, startedAt);
        long batchId = batches.insert(batch);
        Written written = target.write(batchId);
        int duplicates = collector.valid - written.inserted() - written.updated();
        batches.complete(batchId, new BatchRow(batch.dataset(), fileName, sha256, updatedAt, batch.status(),
                rowsRead, written.inserted(), written.updated(), duplicates, collector.rejections.size(), null,
                startedAt, OffsetDateTime.now()));
        batches.insertRejections(batchId, collector.rejections);

        List<Rejection> reported = collector.rejections.stream().limit(REPORTED_REJECTIONS)
                .map(r -> new Rejection(r.line(), r.reason())).toList();
        return new FileReport(dataset, fileName, FileStatus.COMPLETED, updatedAt, sha256, rowsRead,
                written.inserted(), written.updated(), duplicates, collector.rejections.size(), reported, null);
    }

    private static String sha256(Path path) {
        try (DigestInputStream input = new DigestInputStream(Files.newInputStream(path),
                MessageDigest.getInstance("SHA-256"))) {
            input.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(input.getMessageDigest().digest());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Leitor de um tipo de arquivo. */
    @FunctionalInterface
    private interface Parser<T> {
        LocalDate parse(InputStream input, Consumer<ParsedLine<T>> sink) throws IOException;
    }

    /** Destino das linhas válidas de um tipo de arquivo. */
    private interface Target<T> {

        void prepare();

        void stage(List<ParsedLine.Valid<T>> lines);

        Written write(long batchId);
    }

    /** Registros gravados ao final do arquivo. */
    private record Written(int inserted, int updated) {
    }

    /** Acumula linhas válidas em blocos para a staging e guarda todas as rejeições. */
    private static final class Collector<T> implements Consumer<ParsedLine<T>> {

        private final Target<T> target;
        private final List<ParsedLine.Valid<T>> chunk = new ArrayList<>(STAGING_CHUNK);
        private final List<RejectionRow> rejections = new ArrayList<>();
        private int valid;

        Collector(Target<T> target) {
            this.target = target;
        }

        @Override
        public void accept(ParsedLine<T> line) {
            switch (line) {
                case ParsedLine.Valid<T> v -> {
                    valid++;
                    chunk.add(v);
                    if (chunk.size() == STAGING_CHUNK) {
                        flush();
                    }
                }
                case ParsedLine.Rejected<T> r -> rejections.add(new RejectionRow(r.line(), r.reason()));
            }
        }

        void flush() {
            if (!chunk.isEmpty()) {
                target.stage(List.copyOf(chunk));
                chunk.clear();
            }
        }
    }
}
