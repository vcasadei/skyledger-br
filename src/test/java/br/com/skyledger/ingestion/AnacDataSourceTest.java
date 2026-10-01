package br.com.skyledger.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Resolução dos arquivos do {@code anac-data}, com a raiz num diretório temporário.
 */
class AnacDataSourceTest {

    @TempDir
    private Path root;

    /** Mês sem zero à esquerda é ordenado numericamente e nomes fora do padrão são ignorados. */
    @Test
    void ordersVraFilesChronologicallyAndIgnoresOthers() throws IOException {
        createAerodromes();
        Path vra = Files.createDirectories(root.resolve("data/raw/anac/vra"));
        Files.createFile(vra.resolve("VRA_202610.csv"));
        Files.createFile(vra.resolve("VRA_20269.csv"));
        Files.createFile(vra.resolve("outro.csv"));
        Files.createFile(vra.resolve("VRA_202613.csv"));

        AnacDataSource.SourceFiles files = source(root.toString()).resolve();

        assertThat(files.vra()).extracting(f -> f.path().getFileName().toString())
                .containsExactly("VRA_20269.csv", "VRA_202610.csv");
        assertThat(files.vra()).extracting(AnacDataSource.VraFile::period)
                .containsExactly(YearMonth.of(2026, 9), YearMonth.of(2026, 10));
        assertThat(files.aerodromes()).endsWith(Path.of("AerodromosPublicos.csv"));
    }

    /** Propriedade vazia ou ausente cita a propriedade. */
    @Test
    void rejectsMissingPath() {
        assertThatThrownBy(() -> source("").resolve()).isInstanceOf(AnacDataUnavailableException.class)
                .hasMessageContaining("skyledger.anac-data.path");
        assertThatThrownBy(() -> source(null).resolve()).hasMessageContaining("skyledger.anac-data.path");
    }

    /** Raiz inexistente cita o caminho. */
    @Test
    void rejectsNonexistentPath() {
        Path missing = root.resolve("nao-existe");
        assertThatThrownBy(() -> source(missing.toString()).resolve())
                .isInstanceOf(AnacDataUnavailableException.class)
                .hasMessageContaining(missing.toString());
    }

    /** Raiz sem o cadastro de aeródromos. */
    @Test
    void rejectsMissingAerodromes() throws IOException {
        Files.createDirectories(root.resolve("data/raw/anac/vra"));
        assertThatThrownBy(() -> source(root.toString()).resolve())
                .hasMessageContaining("AerodromosPublicos.csv");
    }

    /** Raiz sem nenhum arquivo do VRA. */
    @Test
    void rejectsSourceWithoutVraFiles() throws IOException {
        createAerodromes();
        Files.createDirectories(root.resolve("data/raw/anac/vra"));
        assertThatThrownBy(() -> source(root.toString()).resolve())
                .isInstanceOf(AnacDataUnavailableException.class)
                .hasMessageContaining("Nenhum arquivo do VRA");
    }

    private void createAerodromes() throws IOException {
        Path dir = Files.createDirectories(root.resolve("data/raw/anac/aerodromos"));
        Files.createFile(dir.resolve("AerodromosPublicos.csv"));
    }

    private static AnacDataSource source(String path) {
        return new AnacDataSource(new AnacDataProperties(path));
    }
}
