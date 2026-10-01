package br.com.skyledger.ingestion.parser;

import java.math.BigDecimal;

/**
 * Linha válida do cadastro de Aeródromos Públicos. Campos vazios no arquivo são {@code null}.
 *
 * @param icao           código OACI (ICAO)
 * @param ciad           código CIAD
 * @param name           nome do aeródromo
 * @param municipality   município, ou {@code null}
 * @param uf             UF como publicada (por extenso), ou {@code null}
 * @param latitude       latitude em graus decimais
 * @param longitude      longitude em graus decimais
 * @param altitude       altitude em metros, ou {@code null} se ausente
 * @param registryStatus situação do registro ({@code Cadastrado} ou {@code Interditado})
 */
public record AerodromeRecord(
        String icao,
        String ciad,
        String name,
        String municipality,
        String uf,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal altitude,
        String registryStatus) {
}
