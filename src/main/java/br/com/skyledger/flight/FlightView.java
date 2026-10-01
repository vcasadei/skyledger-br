package br.com.skyledger.flight;

import java.time.LocalDateTime;

/**
 * Voo retornado pela consulta. Horários como gravados pela ingestão, sem fuso.
 *
 * @param airline               código ICAO da companhia
 * @param flightNumber          número do voo, como publicado
 * @param origin                aeroporto de origem (ICAO)
 * @param destination           aeroporto de destino (ICAO)
 * @param scheduledDeparture    partida prevista, ou {@code null}
 * @param actualDeparture       partida real, ou {@code null}
 * @param scheduledArrival      chegada prevista, ou {@code null}
 * @param actualArrival         chegada real, ou {@code null}
 * @param status                {@code REALIZADO} ou {@code CANCELADO}
 * @param departureDelayMinutes partida real menos prevista em minutos (negativo se adiantado),
 *                              ou {@code null} sem os dois horários
 */
public record FlightView(String airline, String flightNumber, String origin, String destination,
        LocalDateTime scheduledDeparture, LocalDateTime actualDeparture, LocalDateTime scheduledArrival,
        LocalDateTime actualArrival, String status, Integer departureDelayMinutes) {
}
