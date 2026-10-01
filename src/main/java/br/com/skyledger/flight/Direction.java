package br.com.skyledger.flight;

/**
 * Sentido dos voos consultados em relação ao aeroporto.
 */
public enum Direction {
    /** Voos que saem do aeroporto (origem). */
    DEPARTURE,
    /** Voos que chegam ao aeroporto (destino). */
    ARRIVAL,
    /** Voos que saem ou chegam. */
    BOTH
}
