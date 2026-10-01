package br.com.skyledger.flight;

import java.util.Arrays;
import java.util.Optional;

/**
 * Campos de ordenação permitidos e a expressão SQL fixa de cada um.
 *
 * <p>A expressão nunca vem da requisição: o nome recebido em {@code sort} só
 * seleciona uma das constantes.
 */
public enum SortField {
    /** Partida de referência: prevista, ou real quando não houver prevista. */
    DEPARTURE("departure", "COALESCE(f.scheduled_departure, f.actual_departure)"),
    /** Chegada de referência: prevista, ou real quando não houver prevista. */
    ARRIVAL("arrival", "COALESCE(f.scheduled_arrival, f.actual_arrival)"),
    /** Código da companhia. */
    AIRLINE("airline", "f.airline_icao"),
    /** Número do voo, como texto. */
    FLIGHT_NUMBER("flightNumber", "f.flight_number"),
    /** Atraso de partida em minutos. */
    DELAY("delay", "(EXTRACT(EPOCH FROM f.actual_departure - f.scheduled_departure) / 60)::integer");

    private final String parameterName;
    private final String sqlExpression;

    SortField(String parameterName, String sqlExpression) {
        this.parameterName = parameterName;
        this.sqlExpression = sqlExpression;
    }

    /**
     * Nome do campo no parâmetro {@code sort}.
     *
     * @return nome público do campo
     */
    public String parameterName() {
        return parameterName;
    }

    /**
     * Expressão SQL de ordenação.
     *
     * @return expressão fixa sobre a tabela {@code flight f}
     */
    String sqlExpression() {
        return sqlExpression;
    }

    /**
     * Procura o campo pelo nome público.
     *
     * @param name nome recebido no parâmetro {@code sort}
     * @return o campo, se for permitido
     */
    static Optional<SortField> fromParameter(String name) {
        return Arrays.stream(values()).filter(field -> field.parameterName.equals(name)).findFirst();
    }
}
