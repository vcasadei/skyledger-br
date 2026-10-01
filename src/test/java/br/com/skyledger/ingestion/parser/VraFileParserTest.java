package br.com.skyledger.ingestion.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Testes unitários do {@link VraFileParser}, sobre conteúdo em memória.
 */
class VraFileParserTest {

    private static final String BOM = "﻿";

    private static final String METADATA = "Atualizado em: 2026-08-28";

    private static final String HEADER = "\"ICAO Empresa Aérea\";\"Número Voo\";\"Código Autorização (DI)\";"
            + "\"Código Tipo Linha\";\"ICAO Aeródromo Origem\";\"ICAO Aeródromo Destino\";\"Partida Prevista\";"
            + "\"Partida Real\";\"Chegada Prevista\";\"Chegada Real\";\"Situação Voo\";\"Código Justificativa\"";

    private static final String REALIZED = row("AZU", "4840", "SBKP", "SBBE", "2026-05-11 08:30:00",
            "2026-05-11 08:38:00", "2026-05-11 11:55:00", "2026-05-11 12:00:00", "REALIZADO");

    private final VraFileParser parser = new VraFileParser();

    /** Lê metadado, cabeçalho e uma linha válida com BOM e CRLF. */
    @Test
    void readsValidFileWithBomAndCrlf() throws IOException {
        Result result = parse(BOM + METADATA + "\r\n" + HEADER + "\r\n" + REALIZED + "\r\n");

        assertThat(result.updatedAt()).isEqualTo(LocalDate.of(2026, 8, 28));
        VraRecord record = result.single();
        assertThat(record.airlineIcao()).isEqualTo("AZU");
        assertThat(record.originIcao()).isEqualTo("SBKP");
        assertThat(record.scheduledDeparture()).isEqualTo(LocalDateTime.of(2026, 5, 11, 8, 30));
        assertThat(record.actualArrival()).isEqualTo(LocalDateTime.of(2026, 5, 11, 12, 0));
        assertThat(record.status()).isEqualTo(FlightStatus.REALIZADO);
        assertThat(record.justificationCode()).isEqualTo("N/A");
    }

    /** Cenário "Arquivo sem BOM". */
    @Test
    void readsFileWithoutBom() throws IOException {
        assertThat(parse(METADATA + "\n" + HEADER + "\n" + REALIZED + "\n").lines()).hasSize(1);
    }

    /** Cenário "Colunas em outra ordem": valores associados pelo nome do cabeçalho. */
    @Test
    void mapsColumnsByName() throws IOException {
        List<String> header = new ArrayList<>(List.of(HEADER.split(";")));
        List<String> values = new ArrayList<>(List.of(REALIZED.split(";")));
        // Troca "ICAO Aeródromo Origem" (4) com "Situação Voo" (10) no cabeçalho e na linha.
        Collections.swap(header, 4, 10);
        Collections.swap(values, 4, 10);

        VraRecord record = parse(METADATA + "\n" + String.join(";", header) + "\n" + String.join(";", values))
                .single();

        assertThat(record.originIcao()).isEqualTo("SBKP");
        assertThat(record.status()).isEqualTo(FlightStatus.REALIZADO);
    }

    /** Cenário "Metadado ausente": a linha 1 já é o cabeçalho. */
    @Test
    void rejectsFileWithoutMetadata() {
        assertThatThrownBy(() -> parse(HEADER + "\n" + REALIZED))
                .isInstanceOf(InvalidSourceFileException.class)
                .hasMessageContaining("Linha 1");
    }

    /** Cenário "Coluna ausente no cabeçalho". */
    @Test
    void rejectsHeaderWithoutColumn() {
        String header = HEADER.replace(";\"Partida Real\"", ";\"Outra Coluna\"");
        assertThatThrownBy(() -> parse(METADATA + "\n" + header + "\n" + REALIZED))
                .isInstanceOf(InvalidSourceFileException.class)
                .hasMessageContaining("Partida Real");
    }

    /** Arquivo sem linha 2. */
    @Test
    void rejectsFileWithoutHeader() {
        assertThatThrownBy(() -> parse(METADATA + "\n")).hasMessageContaining("Linha 2");
    }

    /** Cenário "Voo realizado sem horário previsto". */
    @Test
    void acceptsRealizedFlightWithoutScheduledTimes() throws IOException {
        VraRecord record = parseRow(row("GLO", "1015", "SBRJ", "SBSP", "null", "2026-07-18 10:24:00", "null",
                "2026-07-18 11:20:00", "REALIZADO"));

        assertThat(record.status()).isEqualTo(FlightStatus.REALIZADO);
        assertThat(record.scheduledDeparture()).isNull();
        assertThat(record.scheduledArrival()).isNull();
        assertThat(record.actualDeparture()).isEqualTo(LocalDateTime.of(2026, 7, 18, 10, 24));
    }

    /** Cenário "Voo cancelado". */
    @Test
    void acceptsCancelledFlight() throws IOException {
        VraRecord record = parseRow(row("GLO", "1015", "SBRJ", "SBSP", "2026-07-18 10:30:00", "null",
                "2026-07-18 11:35:00", "null", "CANCELADO"));

        assertThat(record.status()).isEqualTo(FlightStatus.CANCELADO);
        assertThat(record.scheduledDeparture()).isEqualTo(LocalDateTime.of(2026, 7, 18, 10, 30));
        assertThat(record.actualDeparture()).isNull();
        assertThat(record.actualArrival()).isNull();
    }

    /** Cenário "Fração de segundo no horário". */
    @Test
    void dropsFractionOfSecond() throws IOException {
        VraRecord record = parseRow(row("AAL", "0995", "KMIA", "SBGR", "2026-05-21 00:00:00.100000000",
                "2026-05-20 23:55:00", "2026-05-21 08:10:00.100000000", "2026-05-21 08:04:00", "REALIZADO"));

        assertThat(record.scheduledDeparture()).isEqualTo(LocalDateTime.of(2026, 5, 21, 0, 0, 0));
        assertThat(record.scheduledArrival()).isEqualTo(LocalDateTime.of(2026, 5, 21, 8, 10, 0));
    }

    /** Cenário "Número do voo alfanumérico e com zero à esquerda". */
    @Test
    void keepsFlightNumberAsPublished() throws IOException {
        assertThat(parseRow(REALIZED.replace("\"4840\"", "\"Z9610\"")).flightNumber()).isEqualTo("Z9610");
        assertThat(parseRow(REALIZED.replace("\"4840\"", "\"0073\"")).flightNumber()).isEqualTo("0073");
        assertThat(parseRow(REALIZED.replace("\"0\";\"N\"", "\"D\";\"N\"")).authorizationCode()).isEqualTo("D");
    }

    /** Cenário "Combinação inválida de situação e horários": rejeita a linha e segue o arquivo. */
    @Test
    void rejectsCancelledFlightWithActualDepartureAndKeepsGoing() throws IOException {
        String cancelledWithActual = row("GLO", "1015", "SBRJ", "SBSP", "2026-07-18 10:30:00",
                "2026-07-18 10:31:00", "2026-07-18 11:35:00", "null", "CANCELADO");

        Result result = parse(METADATA + "\n" + HEADER + "\n" + cancelledWithActual + "\n" + REALIZED);

        assertThat(result.rejected()).singleElement().satisfies(rejected -> {
            assertThat(rejected.line()).isEqualTo(3);
            assertThat(rejected.reason()).contains("CANCELADO", "Partida Real");
        });
        assertThat(result.valid()).singleElement().extracting(ParsedLine.Valid::line).isEqualTo(4);
    }

    /** REALIZADO sem horário real e com só um horário previsto são rejeitados. */
    @Test
    void rejectsOtherInvalidCombinations() throws IOException {
        assertThat(rejection(row("GLO", "1015", "SBRJ", "SBSP", "2026-07-18 10:30:00", "null",
                "2026-07-18 11:35:00", "null", "REALIZADO"))).contains("REALIZADO", "Partida Real");
        assertThat(rejection(row("GLO", "1015", "SBRJ", "SBSP", "null", "2026-07-18 10:24:00",
                "2026-07-18 11:35:00", "2026-07-18 11:20:00", "REALIZADO"))).contains("previstos");
    }

    /** Cenário "Horário mal formado". */
    @Test
    void rejectsMalformedTimestamp() throws IOException {
        assertThat(rejection(REALIZED.replace("\"2026-05-11 08:38:00\"", "\"21/05/2026 10:00\"")))
                .contains("Partida Real");
        assertThat(rejection(REALIZED.replace("\"2026-05-11 08:38:00\"", "\"2026-02-30 10:00:00\"")))
                .contains("Partida Real");
    }

    /** Cenário "Número de campos incorreto". */
    @Test
    void rejectsWrongFieldCount() throws IOException {
        String elevenFields = REALIZED.substring(0, REALIZED.lastIndexOf(';'));
        assertThat(rejection(elevenFields)).contains("12 campos", "encontrados 11");
    }

    /** Situação desconhecida e códigos inválidos. */
    @Test
    void rejectsUnknownStatusAndInvalidCodes() throws IOException {
        assertThat(rejection(REALIZED.replace("\"REALIZADO\"", "\"ATRASADO\""))).contains("Situação Voo");
        assertThat(rejection(REALIZED.replace("\"SBKP\"", "\"SBK\""))).contains("ICAO Aeródromo Origem");
        assertThat(rejection(REALIZED.replace("\"AZU\"", "\"az\""))).contains("ICAO Empresa Aérea");
        assertThat(rejection(REALIZED.replace("\"4840\"", "\"12345678901\""))).contains("Número Voo");
    }

    /** Códigos de aeroporto com dígitos (medidos no VRA) são aceitos. */
    @Test
    void acceptsAirportCodesWithDigits() throws IOException {
        assertThat(parseRow(REALIZED.replace("\"SBBE\"", "\"SD9A\"")).destinationIcao()).isEqualTo("SD9A");
    }

    private static String row(String airline, String number, String origin, String destination,
            String scheduledDeparture, String actualDeparture, String scheduledArrival, String actualArrival,
            String status) {
        return String.join(";", List.of(airline, number, "0", "N", origin, destination, scheduledDeparture,
                actualDeparture, scheduledArrival, actualArrival, status, "N/A").stream()
                .map(value -> "\"" + value + "\"").toList());
    }

    private VraRecord parseRow(String row) throws IOException {
        return parse(METADATA + "\n" + HEADER + "\n" + row).single();
    }

    private String rejection(String row) throws IOException {
        Result result = parse(METADATA + "\n" + HEADER + "\n" + row);
        assertThat(result.rejected()).as("linha rejeitada").hasSize(1);
        return result.rejected().get(0).reason();
    }

    private Result parse(String content) throws IOException {
        List<ParsedLine<VraRecord>> lines = new ArrayList<>();
        LocalDate updatedAt = parser.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                lines::add);
        return new Result(updatedAt, lines);
    }

    private record Result(LocalDate updatedAt, List<ParsedLine<VraRecord>> lines) {

        List<ParsedLine.Valid<VraRecord>> valid() {
            return lines.stream().filter(ParsedLine.Valid.class::isInstance)
                    .map(line -> (ParsedLine.Valid<VraRecord>) line).toList();
        }

        List<ParsedLine.Rejected<VraRecord>> rejected() {
            return lines.stream().filter(ParsedLine.Rejected.class::isInstance)
                    .map(line -> (ParsedLine.Rejected<VraRecord>) line).toList();
        }

        VraRecord single() {
            assertThat(rejected()).as("rejeições").isEmpty();
            assertThat(valid()).hasSize(1);
            return valid().get(0).record();
        }
    }
}
