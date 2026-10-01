package br.com.skyledger.ingestion.parser;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Metadado (linha 1) e cabeçalho (linha 2) comuns aos arquivos da ANAC.
 */
final class SourceFileHeader {

    private static final Pattern METADATA = Pattern.compile("^Atualizado em: (\\d{4}-\\d{2}-\\d{2})$");

    private SourceFileHeader() {
    }

    /**
     * Lê a data de atualização da linha 1.
     *
     * @param line conteúdo da linha 1, já sem BOM; {@code null} se o arquivo estiver vazio
     * @return data de "Atualizado em"
     * @throws InvalidSourceFileException se a linha não for o metadado esperado
     */
    static LocalDate metadata(String line) {
        Matcher matcher = line == null ? null : METADATA.matcher(line);
        if (matcher == null || !matcher.matches()) {
            throw new InvalidSourceFileException(
                    "Linha 1 não é o metadado \"Atualizado em: AAAA-MM-DD\": " + preview(line));
        }
        try {
            return LocalDate.parse(matcher.group(1));
        } catch (DateTimeParseException ex) {
            throw new InvalidSourceFileException("Linha 1 tem data inválida: " + matcher.group(1));
        }
    }

    /**
     * Mapeia o nome de cada coluna esperada para sua posição no cabeçalho.
     *
     * @param names    nomes das colunas na ordem da linha 2; {@code null} se não houver linha 2
     * @param expected colunas obrigatórias
     * @return posição de cada coluna esperada
     * @throws InvalidSourceFileException se faltar, sobrar ou repetir coluna
     */
    static Map<String, Integer> columns(List<String> names, Set<String> expected) {
        if (names == null) {
            throw new InvalidSourceFileException("Linha 2 (cabeçalho) ausente");
        }
        Map<String, Integer> index = new HashMap<>();
        List<String> unexpected = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (!expected.contains(name) || index.putIfAbsent(name, i) != null) {
                unexpected.add(name);
            }
        }
        Set<String> missing = new LinkedHashSet<>(expected);
        missing.removeAll(index.keySet());
        if (!missing.isEmpty() || !unexpected.isEmpty()) {
            throw new InvalidSourceFileException("Linha 2 (cabeçalho) inválida: colunas ausentes " + missing
                    + ", inesperadas ou repetidas " + unexpected);
        }
        return index;
    }

    private static String preview(String line) {
        if (line == null) {
            return "(arquivo vazio)";
        }
        return line.length() > 80 ? line.substring(0, 80) + "..." : line;
    }
}
