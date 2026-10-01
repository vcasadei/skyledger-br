package br.com.skyledger.ingestion.parser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Leitor estrito dos arquivos mensais do VRA (Voo Regular Ativo).
 *
 * <p>Formato: UTF-8 com BOM opcional, linha 1 de metadado, linha 2 de cabeçalho
 * e linhas de dados com exatamente 12 campos entre aspas separados por {@code ;},
 * sem aspas nem {@code ;} dentro dos valores. Linhas fora das regras viram
 * {@link ParsedLine.Rejected} sem interromper o arquivo.
 */
@Component
public class VraFileParser {

    static final String AIRLINE = "ICAO Empresa Aérea";
    static final String FLIGHT_NUMBER = "Número Voo";
    static final String AUTHORIZATION = "Código Autorização (DI)";
    static final String LINE_TYPE = "Código Tipo Linha";
    static final String ORIGIN = "ICAO Aeródromo Origem";
    static final String DESTINATION = "ICAO Aeródromo Destino";
    static final String SCHEDULED_DEPARTURE = "Partida Prevista";
    static final String ACTUAL_DEPARTURE = "Partida Real";
    static final String SCHEDULED_ARRIVAL = "Chegada Prevista";
    static final String ACTUAL_ARRIVAL = "Chegada Real";
    static final String STATUS = "Situação Voo";
    static final String JUSTIFICATION = "Código Justificativa";

    private static final LinkedHashSet<String> COLUMNS = new LinkedHashSet<>(List.of(AIRLINE, FLIGHT_NUMBER,
            AUTHORIZATION, LINE_TYPE, ORIGIN, DESTINATION, SCHEDULED_DEPARTURE, ACTUAL_DEPARTURE,
            SCHEDULED_ARRIVAL, ACTUAL_ARRIVAL, STATUS, JUSTIFICATION));

    private static final int FIELD_COUNT = 12;

    private static final Pattern QUOTED_ROW = Pattern.compile("^\"[^\";]*\"(;\"[^\";]*\"){11}$");

    private static final Pattern TIMESTAMP = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})(\\.\\d+)?$");

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    private static final Pattern AIRLINE_CODE = Pattern.compile("^[A-Z0-9]{3}$");

    private static final Pattern AIRPORT_CODE = Pattern.compile("^[A-Z0-9]{4}$");

    private static final String NULL_LITERAL = "null";

    private static final int MAX_FLIGHT_NUMBER_LENGTH = 10;

    /**
     * Lê um arquivo do VRA, entregando cada linha de dados ao {@code sink}.
     *
     * @param input conteúdo do arquivo
     * @param sink  recebe cada linha válida ou rejeitada, na ordem do arquivo
     * @return data de "Atualizado em" do metadado
     * @throws InvalidSourceFileException se o metadado ou o cabeçalho estiverem fora do layout
     * @throws IOException                se a leitura falhar
     */
    public LocalDate parse(InputStream input, Consumer<ParsedLine<VraRecord>> sink) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        LocalDate updatedAt = SourceFileHeader.metadata(stripBom(reader.readLine()));
        String header = reader.readLine();
        Map<String, Integer> columns = SourceFileHeader.columns(header == null ? null : headerNames(header), COLUMNS);

        int lineNumber = 2;
        String line;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.isEmpty()) {
                continue;
            }
            sink.accept(parseLine(lineNumber, line, columns));
        }
        return updatedAt;
    }

    private static ParsedLine<VraRecord> parseLine(int lineNumber, String line, Map<String, Integer> columns) {
        Optional<List<String>> fields = quotedFields(line);
        if (fields.isEmpty()) {
            return new ParsedLine.Rejected<>(lineNumber, "Esperados " + FIELD_COUNT
                    + " campos entre aspas separados por ';', encontrados " + line.split(";", -1).length);
        }
        try {
            return new ParsedLine.Valid<>(lineNumber, toRecord(new Row(fields.get(), columns)));
        } catch (InvalidValueException ex) {
            return new ParsedLine.Rejected<>(lineNumber, ex.getMessage());
        }
    }

    private static VraRecord toRecord(Row row) {
        String airline = code(row, AIRLINE, AIRLINE_CODE);
        String flightNumber = row.get(FLIGHT_NUMBER);
        if (flightNumber.isEmpty() || flightNumber.length() > MAX_FLIGHT_NUMBER_LENGTH) {
            throw new InvalidValueException(FLIGHT_NUMBER + " deve ter de 1 a " + MAX_FLIGHT_NUMBER_LENGTH
                    + " caracteres: \"" + flightNumber + "\"");
        }
        String origin = code(row, ORIGIN, AIRPORT_CODE);
        String destination = code(row, DESTINATION, AIRPORT_CODE);
        LocalDateTime scheduledDeparture = timestamp(row, SCHEDULED_DEPARTURE);
        LocalDateTime actualDeparture = timestamp(row, ACTUAL_DEPARTURE);
        LocalDateTime scheduledArrival = timestamp(row, SCHEDULED_ARRIVAL);
        LocalDateTime actualArrival = timestamp(row, ACTUAL_ARRIVAL);
        FlightStatus status = status(row.get(STATUS));
        checkTimesForStatus(status, scheduledDeparture, actualDeparture, scheduledArrival, actualArrival);
        return new VraRecord(airline, flightNumber, row.get(AUTHORIZATION), row.get(LINE_TYPE), origin, destination,
                scheduledDeparture, actualDeparture, scheduledArrival, actualArrival, status, row.get(JUSTIFICATION));
    }

    private static void checkTimesForStatus(FlightStatus status, LocalDateTime scheduledDeparture,
            LocalDateTime actualDeparture, LocalDateTime scheduledArrival, LocalDateTime actualArrival) {
        if (status == FlightStatus.REALIZADO) {
            if (actualDeparture == null || actualArrival == null) {
                throw new InvalidValueException("Situação REALIZADO exige " + ACTUAL_DEPARTURE + " e "
                        + ACTUAL_ARRIVAL + " preenchidas");
            }
            if ((scheduledDeparture == null) != (scheduledArrival == null)) {
                throw new InvalidValueException("Situação REALIZADO com só um dos horários previstos ("
                        + SCHEDULED_DEPARTURE + "/" + SCHEDULED_ARRIVAL + ") preenchido");
            }
            return;
        }
        if (actualDeparture != null) {
            throw new InvalidValueException("Situação CANCELADO com " + ACTUAL_DEPARTURE + " preenchida");
        }
        if (actualArrival != null) {
            throw new InvalidValueException("Situação CANCELADO com " + ACTUAL_ARRIVAL + " preenchida");
        }
        if (scheduledDeparture == null || scheduledArrival == null) {
            throw new InvalidValueException("Situação CANCELADO exige " + SCHEDULED_DEPARTURE + " e "
                    + SCHEDULED_ARRIVAL + " preenchidas");
        }
    }

    private static FlightStatus status(String value) {
        try {
            return FlightStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new InvalidValueException(STATUS + " desconhecida: \"" + value + "\"");
        }
    }

    private static String code(Row row, String column, Pattern format) {
        String value = row.get(column);
        if (!format.matcher(value).matches()) {
            throw new InvalidValueException(column + " inválido: \"" + value + "\"");
        }
        return value;
    }

    private static LocalDateTime timestamp(Row row, String column) {
        String value = row.get(column);
        if (NULL_LITERAL.equals(value)) {
            return null;
        }
        Matcher matcher = TIMESTAMP.matcher(value);
        if (!matcher.matches()) {
            throw new InvalidValueException(column + " fora do formato AAAA-MM-DD HH:MM:SS: \"" + value + "\"");
        }
        try {
            // A fração (.100000000 nos horários previstos) é descartada.
            return LocalDateTime.parse(matcher.group(1), TIMESTAMP_FORMAT);
        } catch (DateTimeParseException ex) {
            throw new InvalidValueException(column + " com data ou hora inexistente: \"" + value + "\"");
        }
    }

    private static List<String> headerNames(String header) {
        return quotedFields(header).orElseThrow(() -> new InvalidSourceFileException(
                "Linha 2 (cabeçalho) fora do formato de " + FIELD_COUNT + " campos entre aspas separados por ';'"));
    }

    private static Optional<List<String>> quotedFields(String line) {
        if (!QUOTED_ROW.matcher(line).matches()) {
            return Optional.empty();
        }
        List<String> fields = new ArrayList<>(FIELD_COUNT);
        for (String quoted : line.split(";", -1)) {
            fields.add(quoted.substring(1, quoted.length() - 1));
        }
        return Optional.of(fields);
    }

    private static String stripBom(String line) {
        return line != null && line.startsWith("﻿") ? line.substring(1) : line;
    }

    /** Campos de uma linha acessados pelo nome da coluna. */
    private record Row(List<String> fields, Map<String, Integer> columns) {

        String get(String column) {
            return fields.get(columns.get(column));
        }
    }

}
