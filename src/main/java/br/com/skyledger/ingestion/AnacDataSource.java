package br.com.skyledger.ingestion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Localiza os arquivos de entrada dentro da raiz do {@code anac-data} configurada.
 */
@Component
public class AnacDataSource {

    static final String PROPERTY = "skyledger.anac-data.path";

    private static final Path AERODROMES = Path.of("data", "raw", "anac", "aerodromos", "AerodromosPublicos.csv");

    private static final Path VRA_DIR = Path.of("data", "raw", "anac", "vra");

    private static final Pattern VRA_NAME = Pattern.compile("^VRA_(\\d{4})(\\d{1,2})\\.csv$");

    private final AnacDataProperties properties;

    /**
     * Cria o localizador sobre a configuração da fonte.
     *
     * @param properties configuração com a raiz do {@code anac-data}
     */
    public AnacDataSource(AnacDataProperties properties) {
        this.properties = properties;
    }

    /**
     * Resolve o cadastro de aeródromos e os arquivos do VRA, em ordem cronológica.
     *
     * @return arquivos a processar
     * @throws AnacDataUnavailableException se a raiz não estiver configurada ou não
     *                                      existir, se o cadastro faltar ou se não houver arquivos do VRA
     */
    public SourceFiles resolve() {
        String configured = properties.path();
        if (configured == null || configured.isBlank()) {
            throw new AnacDataUnavailableException(
                    "Propriedade " + PROPERTY + " não configurada: defina ANAC_DATA_PATH com a raiz do anac-data");
        }
        Path root = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new AnacDataUnavailableException("Diretório do anac-data não encontrado: " + root
                    + " (" + PROPERTY + ")");
        }
        Path aerodromes = root.resolve(AERODROMES);
        if (!Files.isRegularFile(aerodromes)) {
            throw new AnacDataUnavailableException("Cadastro de aeródromos não encontrado: " + aerodromes);
        }
        List<VraFile> vra = vraFiles(root.resolve(VRA_DIR));
        if (vra.isEmpty()) {
            throw new AnacDataUnavailableException(
                    "Nenhum arquivo do VRA (VRA_<AAAA><M>.csv) em " + root.resolve(VRA_DIR));
        }
        return new SourceFiles(aerodromes, vra);
    }

    private static List<VraFile> vraFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isRegularFile)
                    .map(AnacDataSource::vraFile)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(VraFile::period))
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static Optional<VraFile> vraFile(Path path) {
        Matcher name = VRA_NAME.matcher(path.getFileName().toString());
        if (!name.matches()) {
            return Optional.empty();
        }
        int month = Integer.parseInt(name.group(2));
        if (month < 1 || month > 12) {
            return Optional.empty();
        }
        return Optional.of(new VraFile(path, YearMonth.of(Integer.parseInt(name.group(1)), month)));
    }

    /**
     * Arquivos de entrada resolvidos.
     *
     * @param aerodromes cadastro de Aeródromos Públicos
     * @param vra        arquivos do VRA em ordem cronológica
     */
    public record SourceFiles(Path aerodromes, List<VraFile> vra) {
    }

    /**
     * Arquivo mensal do VRA.
     *
     * @param path   caminho do arquivo
     * @param period ano e mês lidos do nome
     */
    public record VraFile(Path path, YearMonth period) {
    }
}
