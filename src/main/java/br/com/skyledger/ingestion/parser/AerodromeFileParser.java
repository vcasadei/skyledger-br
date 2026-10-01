package br.com.skyledger.ingestion.parser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Leitor estrito do cadastro de Aeródromos Públicos da ANAC.
 *
 * <p>Formato: ISO-8859-1, linha 1 de metadado, linha 2 de cabeçalho e linhas com
 * exatamente 18 campos separados por {@code ;}, sem aspas. Uma aspa dupla dentro
 * de um valor é um caractere comum (latitude DMS como {@code 09°52'06"S}).
 */
@Component
public class AerodromeFileParser {

    static final String ICAO = "Código OACI";
    static final String CIAD = "CIAD";
    static final String NAME = "Nome";
    static final String MUNICIPALITY = "Município";
    static final String UF = "UF";
    static final String LATITUDE = "LATGEOPOINT";
    static final String LONGITUDE = "LONGEOPOINT";
    static final String ALTITUDE = "Altitude";
    static final String REGISTRY_STATUS = "Situação";

    private static final Set<String> COLUMNS = new LinkedHashSet<>(List.of(ICAO, CIAD, NAME, MUNICIPALITY, UF,
            "Município Servido", "UF Servido", LATITUDE, LONGITUDE, "Latitude", "Longitude", ALTITUDE,
            "Operação Diurna", "Operação Noturna", REGISTRY_STATUS, "Validade do Registro",
            "Portaria de Registro", "Link Portaria"));

    private static final int FIELD_COUNT = 18;

    private static final Pattern ICAO_CODE = Pattern.compile("^[A-Z0-9]{4}$");

    /** Graus decimais com ponto; até 3 dígitos inteiros e 9 decimais, o que cabe exato em numeric(12,9). */
    private static final Pattern COORDINATE = Pattern.compile("^-?\\d{1,3}\\.\\d{1,9}$");

    /** Metros com vírgula decimal; cabe exato em numeric(7,1). */
    private static final Pattern ALTITUDE_VALUE = Pattern.compile("^-?\\d{1,6}(,\\d)?$");

    /** Altitude vazia ou sem parte inteira (",0" no SBMI): valor ausente, não zero. */
    private static final Pattern MISSING_ALTITUDE = Pattern.compile("^(,\\d*)?$");

    private static final Set<String> REGISTRY_STATUSES = Set.of("Cadastrado", "Interditado");

    /**
     * Lê o cadastro, entregando cada linha de dados ao {@code sink}.
     *
     * @param input conteúdo do arquivo
     * @param sink  recebe cada linha válida ou rejeitada, na ordem do arquivo
     * @return data de "Atualizado em" do metadado
     * @throws InvalidSourceFileException se o metadado ou o cabeçalho estiverem fora do layout
     * @throws IOException                se a leitura falhar
     */
    public LocalDate parse(InputStream input, Consumer<ParsedLine<AerodromeRecord>> sink) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.ISO_8859_1));
        LocalDate updatedAt = SourceFileHeader.metadata(reader.readLine());
        String header = reader.readLine();
        Map<String, Integer> columns =
                SourceFileHeader.columns(header == null ? null : List.of(header.split(";", -1)), COLUMNS);

        int lineNumber = 2;
        String line;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.isEmpty()) {
                continue;
            }
            sink.accept(parseLine(lineNumber, line.split(";", -1), columns));
        }
        return updatedAt;
    }

    private static ParsedLine<AerodromeRecord> parseLine(int lineNumber, String[] fields,
            Map<String, Integer> columns) {
        if (fields.length != FIELD_COUNT) {
            return new ParsedLine.Rejected<>(lineNumber,
                    "Esperados " + FIELD_COUNT + " campos separados por ';', encontrados " + fields.length);
        }
        try {
            return new ParsedLine.Valid<>(lineNumber, toRecord(fields, columns));
        } catch (InvalidValueException ex) {
            return new ParsedLine.Rejected<>(lineNumber, ex.getMessage());
        }
    }

    private static AerodromeRecord toRecord(String[] fields, Map<String, Integer> columns) {
        String icao = fields[columns.get(ICAO)];
        if (!ICAO_CODE.matcher(icao).matches()) {
            throw new InvalidValueException(ICAO + " inválido: \"" + icao + "\"");
        }
        String status = fields[columns.get(REGISTRY_STATUS)];
        if (!REGISTRY_STATUSES.contains(status)) {
            throw new InvalidValueException(REGISTRY_STATUS + " desconhecida: \"" + status + "\"");
        }
        return new AerodromeRecord(
                icao,
                emptyToNull(fields[columns.get(CIAD)]),
                emptyToNull(fields[columns.get(NAME)]),
                emptyToNull(fields[columns.get(MUNICIPALITY)]),
                emptyToNull(fields[columns.get(UF)]),
                coordinate(fields[columns.get(LATITUDE)], LATITUDE),
                coordinate(fields[columns.get(LONGITUDE)], LONGITUDE),
                altitude(fields[columns.get(ALTITUDE)]),
                status);
    }

    private static BigDecimal coordinate(String value, String column) {
        if (!COORDINATE.matcher(value).matches()) {
            throw new InvalidValueException(column + " fora do formato de graus decimais com ponto: \"" + value + "\"");
        }
        return new BigDecimal(value);
    }

    private static BigDecimal altitude(String value) {
        if (MISSING_ALTITUDE.matcher(value).matches()) {
            return null;
        }
        if (!ALTITUDE_VALUE.matcher(value).matches()) {
            throw new InvalidValueException(ALTITUDE + " fora do formato de metros com vírgula decimal: \""
                    + value + "\"");
        }
        return new BigDecimal(value.replace(',', '.'));
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }

}
