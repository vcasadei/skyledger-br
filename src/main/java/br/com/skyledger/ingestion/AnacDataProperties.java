package br.com.skyledger.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração da fonte de dados da ANAC.
 *
 * @param path raiz do repositório {@code anac-data}, absoluta ou relativa ao
 *             diretório de trabalho; vazia quando não configurada
 */
@ConfigurationProperties("skyledger.anac-data")
public record AnacDataProperties(String path) {
}
