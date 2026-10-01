package br.com.skyledger.ingestion.parser;

/**
 * Situação do voo publicada no VRA.
 */
public enum FlightStatus {
    /** Voo realizado (com ou sem horário previsto registrado). */
    REALIZADO,
    /** Voo cancelado. */
    CANCELADO
}
