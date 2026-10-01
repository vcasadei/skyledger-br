package br.com.skyledger.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.springframework.core.io.ClassPathResource;

/**
 * Monta uma raiz de {@code anac-data} num diretório temporário, com o layout real
 * ({@code data/raw/anac/{vra,aerodromos}}), a partir das fixtures de teste.
 */
final class AnacDataFixture {

    static final String AERODROMES = "data/raw/anac/aerodromos/AerodromosPublicos.csv";

    static final String VRA_DIR = "data/raw/anac/vra/";

    private final Path root;

    /**
     * Cria o montador sobre a raiz temporária.
     *
     * @param root raiz do {@code anac-data} de teste
     */
    AnacDataFixture(Path root) {
        this.root = root;
    }

    /** Apaga todo o conteúdo da raiz, mantendo o diretório. */
    void clear() {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).filter(path -> !path.equals(root)).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Copia o cadastro e os dois arquivos do VRA das fixtures. */
    void copyAll() {
        copy("fixtures/aerodromos/AerodromosPublicos.csv", AERODROMES);
        copy("fixtures/vra/VRA_20265.csv", VRA_DIR + "VRA_20265.csv");
        copy("fixtures/vra/VRA_20266.csv", VRA_DIR + "VRA_20266.csv");
    }

    /**
     * Copia uma fixture do classpath para um caminho relativo à raiz.
     *
     * @param resource fixture no classpath
     * @param target   caminho relativo à raiz
     */
    void copy(String resource, String target) {
        Path destination = root.resolve(target);
        try (InputStream input = new ClassPathResource(resource).getInputStream()) {
            Files.createDirectories(destination.getParent());
            Files.copy(input, destination);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Grava um arquivo com conteúdo e encoding dados.
     *
     * @param target  caminho relativo à raiz
     * @param content conteúdo
     * @param charset encoding
     */
    void write(String target, String content, Charset charset) {
        Path destination = root.resolve(target);
        try {
            Files.createDirectories(destination.getParent());
            Files.writeString(destination, content, charset);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Raiz montada.
     *
     * @return raiz do {@code anac-data} de teste
     */
    Path root() {
        return root;
    }
}
