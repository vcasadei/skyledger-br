package br.com.skyledger.ingestion.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Testes unitários do {@link AerodromeFileParser}, sobre conteúdo ISO-8859-1 em memória.
 */
class AerodromeFileParserTest {

    private static final String METADATA = "Atualizado em: 2026-09-15";

    private static final String HEADER = "Código OACI;CIAD;Nome;Município;UF;Município Servido;UF Servido;"
            + "LATGEOPOINT;LONGEOPOINT;Latitude;Longitude;Altitude;Operação Diurna;Operação Noturna;Situação;"
            + "Validade do Registro;Portaria de Registro;Link Portaria";

    private static final String SBRB = "SBRB;AC0001;Plácido de Castro;RIO BRANCO;Acre;Rio Branco;Acre;-9.8683333;"
            + "-67.898056;09°52'06\"S;067°53'53\"W;193,0;VFR / IFR - CAT I;VFR / IFR - CAT I;Cadastrado;;;";

    private final AerodromeFileParser parser = new AerodromeFileParser();

    /** Cenário "Aeródromo cadastrado", com a aspa literal da latitude DMS. */
    @Test
    void readsRegisteredAerodrome() throws IOException {
        Result result = parse(METADATA + "\r\n" + HEADER + "\r\n" + SBRB + "\r\n");

        assertThat(result.updatedAt()).isEqualTo(LocalDate.of(2026, 9, 15));
        AerodromeRecord record = result.single();
        assertThat(record.icao()).isEqualTo("SBRB");
        assertThat(record.ciad()).isEqualTo("AC0001");
        assertThat(record.name()).isEqualTo("Plácido de Castro");
        assertThat(record.municipality()).isEqualTo("RIO BRANCO");
        assertThat(record.uf()).isEqualTo("Acre");
        assertThat(record.latitude()).isEqualTo(new BigDecimal("-9.8683333"));
        assertThat(record.longitude()).isEqualTo(new BigDecimal("-67.898056"));
        assertThat(record.altitude()).isEqualTo(new BigDecimal("193.0"));
        assertThat(record.registryStatus()).isEqualTo("Cadastrado");
    }

    /** Escala de 9 casas preservada exatamente (sem ponto flutuante). */
    @Test
    void keepsNineDecimalPlaces() throws IOException {
        AerodromeRecord record = parseRow(SBRB.replace("-9.8683333", "0.050555556"));
        assertThat(record.latitude()).isEqualTo(new BigDecimal("0.050555556"));
        assertThat(record.latitude().scale()).isEqualTo(9);
    }

    /** Cenário "Município ausente". */
    @Test
    void acceptsMissingMunicipalityAndUf() throws IOException {
        AerodromeRecord record = parseRow(SBRB.replace(";RIO BRANCO;Acre;", ";;;"));
        assertThat(record.municipality()).isNull();
        assertThat(record.uf()).isNull();
        assertThat(record.name()).isEqualTo("Plácido de Castro");
        assertThat(record.latitude()).isEqualTo(new BigDecimal("-9.8683333"));
    }

    /** Cenário "Altitude sem parte inteira": vazia ou ",0" vira ausente, não zero. */
    @Test
    void treatsAltitudeWithoutIntegerPartAsMissing() throws IOException {
        AerodromeRecord record = parseRow(SBRB.replace(";193,0;", ";,0;"));
        assertThat(record.altitude()).isNull();
        assertThat(record.name()).isEqualTo("Plácido de Castro");
        assertThat(parseRow(SBRB.replace(";193,0;", ";;")).altitude()).isNull();
    }

    /** Cenário "Altitude mal formada": rejeita a linha e segue o arquivo. */
    @Test
    void rejectsMalformedAltitudeAndKeepsGoing() throws IOException {
        Result result = parse(METADATA + "\n" + HEADER + "\n" + SBRB.replace(";193,0;", ";abc;") + "\n" + SBRB);

        assertThat(result.rejected()).singleElement().satisfies(rejected -> {
            assertThat(rejected.line()).isEqualTo(3);
            assertThat(rejected.reason()).contains("Altitude");
        });
        assertThat(result.valid()).hasSize(1);
    }

    /** Coordenada fora do formato, número de campos, código e situação inválidos. */
    @Test
    void rejectsInvalidRows() throws IOException {
        assertThat(rejection(SBRB.replace("-9.8683333", "-9,8683333"))).contains("LATGEOPOINT");
        assertThat(rejection(SBRB.replace("-9.8683333", "-9.12345678901"))).contains("LATGEOPOINT");
        assertThat(rejection(SBRB.substring(0, SBRB.lastIndexOf(';')))).contains("18 campos", "encontrados 17");
        assertThat(rejection(SBRB.replace("SBRB;", "SB;"))).contains("Código OACI");
        assertThat(rejection(SBRB.replace(";Cadastrado;", ";Desativado;"))).contains("Situação");
    }

    /** Cabeçalho com 17 colunas rejeita o arquivo. */
    @Test
    void rejectsHeaderWithMissingColumn() {
        String header = HEADER.replace(";Link Portaria", "");
        assertThatThrownBy(() -> parse(METADATA + "\n" + header + "\n" + SBRB))
                .isInstanceOf(InvalidSourceFileException.class)
                .hasMessageContaining("Link Portaria");
    }

    /** Metadado ausente rejeita o arquivo. */
    @Test
    void rejectsFileWithoutMetadata() {
        assertThatThrownBy(() -> parse(HEADER + "\n" + SBRB)).hasMessageContaining("Linha 1");
    }

    private AerodromeRecord parseRow(String row) throws IOException {
        return parse(METADATA + "\n" + HEADER + "\n" + row).single();
    }

    private String rejection(String row) throws IOException {
        Result result = parse(METADATA + "\n" + HEADER + "\n" + row);
        assertThat(result.rejected()).as("linha rejeitada").hasSize(1);
        return result.rejected().get(0).reason();
    }

    private Result parse(String content) throws IOException {
        List<ParsedLine<AerodromeRecord>> lines = new ArrayList<>();
        LocalDate updatedAt = parser.parse(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.ISO_8859_1)), lines::add);
        return new Result(updatedAt, lines);
    }

    private record Result(LocalDate updatedAt, List<ParsedLine<AerodromeRecord>> lines) {

        List<ParsedLine.Valid<AerodromeRecord>> valid() {
            return lines.stream().filter(ParsedLine.Valid.class::isInstance)
                    .map(line -> (ParsedLine.Valid<AerodromeRecord>) line).toList();
        }

        List<ParsedLine.Rejected<AerodromeRecord>> rejected() {
            return lines.stream().filter(ParsedLine.Rejected.class::isInstance)
                    .map(line -> (ParsedLine.Rejected<AerodromeRecord>) line).toList();
        }

        AerodromeRecord single() {
            assertThat(rejected()).as("rejeições").isEmpty();
            assertThat(valid()).hasSize(1);
            return valid().get(0).record();
        }
    }
}
