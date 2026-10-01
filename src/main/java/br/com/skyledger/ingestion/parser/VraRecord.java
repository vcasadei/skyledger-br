package br.com.skyledger.ingestion.parser;

import java.time.LocalDateTime;

/**
 * Linha válida do VRA. Horários ausentes ({@code null} literal no arquivo) são {@code null}.
 *
 * @param airlineIcao        código ICAO da empresa
 * @param flightNumber       número do voo, como publicado
 * @param authorizationCode  código de autorização (DI), como publicado
 * @param lineType           código do tipo de linha, como publicado
 * @param originIcao         aeroporto de origem
 * @param destinationIcao    aeroporto de destino
 * @param scheduledDeparture partida prevista, ou {@code null}
 * @param actualDeparture    partida real, ou {@code null}
 * @param scheduledArrival   chegada prevista, ou {@code null}
 * @param actualArrival      chegada real, ou {@code null}
 * @param status             situação do voo
 * @param justificationCode  código de justificativa, como publicado
 */
public record VraRecord(
        String airlineIcao,
        String flightNumber,
        String authorizationCode,
        String lineType,
        String originIcao,
        String destinationIcao,
        LocalDateTime scheduledDeparture,
        LocalDateTime actualDeparture,
        LocalDateTime scheduledArrival,
        LocalDateTime actualArrival,
        FlightStatus status,
        String justificationCode) {
}
