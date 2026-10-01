package br.com.skyledger.flight;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Valida os parâmetros de {@code GET /flights}, recebidos como texto, com
 * mensagens que citam o parâmetro.
 */
public final class FlightQueryParameters {

    /** Tamanho máximo de página. */
    public static final int MAX_SIZE = 100;

    /** Tamanho de página padrão. */
    public static final int DEFAULT_SIZE = 20;

    private static final Pattern ICAO = Pattern.compile("^[A-Z0-9]{4}$");

    private static final Pattern IATA_LIKE = Pattern.compile("^[A-Za-z0-9]{3}$");

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private static final Set<String> STATUSES = Set.of("REALIZADO", "CANCELADO");

    private FlightQueryParameters() {
    }

    /**
     * Valida e converte os parâmetros.
     *
     * @param airport   código ICAO (obrigatório)
     * @param direction {@code DEPARTURE}, {@code ARRIVAL} ou {@code BOTH} (padrão)
     * @param from      data inicial {@code AAAA-MM-DD}, opcional
     * @param to        data final {@code AAAA-MM-DD}, opcional
     * @param status    {@code REALIZADO} ou {@code CANCELADO}, opcional
     * @param page      página a partir de 0 (padrão 0)
     * @param size      tamanho de 1 a {@value #MAX_SIZE} (padrão {@value #DEFAULT_SIZE})
     * @param sort      {@code campo[,asc|desc]} (padrão {@code departure,asc})
     * @return consulta validada
     * @throws InvalidQueryParameterException se algum parâmetro for inválido
     */
    public static FlightQuery parse(String airport, String direction, String from, String to, String status,
            String page, String size, String sort) {
        String icao = airport(airport);
        Direction parsedDirection = isBlank(direction) ? Direction.BOTH : direction(direction);
        LocalDate fromDate = date("from", from);
        LocalDate toDate = date("to", to);
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new InvalidQueryParameterException("from",
                    "Parâmetro from (" + fromDate + ") posterior a to (" + toDate + ")");
        }
        String parsedStatus = status(status);
        int parsedPage = integer("page", page, 0);
        if (parsedPage < 0) {
            throw new InvalidQueryParameterException("page", "Parâmetro page deve ser maior ou igual a 0");
        }
        int parsedSize = integer("size", size, DEFAULT_SIZE);
        if (parsedSize < 1 || parsedSize > MAX_SIZE) {
            throw new InvalidQueryParameterException("size",
                    "Parâmetro size deve estar entre 1 e " + MAX_SIZE + " (máximo " + MAX_SIZE + ")");
        }
        String[] sortParts = isBlank(sort) ? new String[] {"departure"} : sort.split(",", -1);
        return new FlightQuery(icao, parsedDirection, fromDate, toDate, parsedStatus, parsedPage, parsedSize,
                sortField(sortParts[0]), ascending(sortParts));
    }

    private static String airport(String value) {
        if (isBlank(value)) {
            throw new InvalidQueryParameterException("airport",
                    "Parâmetro airport é obrigatório: código ICAO de 4 caracteres (ex.: SBGR)");
        }
        if (ICAO.matcher(value).matches()) {
            return value;
        }
        if (IATA_LIKE.matcher(value).matches()) {
            throw new InvalidQueryParameterException("airport", "Parâmetro airport deve ser um código ICAO de 4 "
                    + "caracteres (ex.: SBGR para Guarulhos); códigos IATA como \"" + value + "\" não são aceitos");
        }
        throw new InvalidQueryParameterException("airport",
                "Parâmetro airport inválido: \"" + value + "\"; esperado código ICAO de 4 caracteres [A-Z0-9]");
    }

    private static Direction direction(String value) {
        try {
            return Direction.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new InvalidQueryParameterException("direction", "Parâmetro direction inválido: \"" + value
                    + "\"; valores permitidos: " + Arrays.toString(Direction.values()));
        }
    }

    private static LocalDate date(String parameter, String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value, DATE);
        } catch (DateTimeParseException ex) {
            throw new InvalidQueryParameterException(parameter, "Parâmetro " + parameter + " inválido: \"" + value
                    + "\"; esperada data existente no formato AAAA-MM-DD");
        }
    }

    private static String status(String value) {
        if (isBlank(value)) {
            return null;
        }
        if (!STATUSES.contains(value)) {
            throw new InvalidQueryParameterException("status", "Parâmetro status inválido: \"" + value
                    + "\"; valores permitidos: [REALIZADO, CANCELADO]");
        }
        return value;
    }

    private static int integer(String parameter, String value, int defaultValue) {
        if (isBlank(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new InvalidQueryParameterException(parameter,
                    "Parâmetro " + parameter + " deve ser um número inteiro: \"" + value + "\"");
        }
    }

    private static SortField sortField(String name) {
        return SortField.fromParameter(name).orElseThrow(() -> new InvalidQueryParameterException("sort",
                "Parâmetro sort com campo desconhecido: \"" + name + "\"; campos permitidos: "
                        + Arrays.stream(SortField.values()).map(SortField::parameterName)
                                .collect(Collectors.joining(", ", "[", "]"))));
    }

    private static boolean ascending(String[] parts) {
        if (parts.length == 1) {
            return true;
        }
        if (parts.length == 2 && "asc".equals(parts[1])) {
            return true;
        }
        if (parts.length == 2 && "desc".equals(parts[1])) {
            return false;
        }
        throw new InvalidQueryParameterException("sort",
                "Parâmetro sort deve ter o formato campo[,asc|desc]: \"" + String.join(",", parts) + "\"");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
