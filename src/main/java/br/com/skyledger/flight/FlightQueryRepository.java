package br.com.skyledger.flight;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Consulta somente leitura de voos por aeroporto.
 *
 * <p>O SQL é montado só a partir de fragmentos fixos; todo valor da requisição
 * vai por parâmetro nomeado, e a ordenação vem da lista branca {@link SortField}.
 */
@Repository
public class FlightQueryRepository {

    private static final String REFERENCE_DEPARTURE = SortField.DEPARTURE.sqlExpression();

    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc acesso JDBC
     */
    public FlightQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Indica se o aeroporto existe.
     *
     * @param icao código ICAO
     * @return {@code true} se houver o aeroporto na tabela {@code airport}
     */
    public boolean airportExists(String icao) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM airport WHERE icao = :icao)")
                .param("icao", icao).query(Boolean.class).single());
    }

    /**
     * Conta os voos que atendem aos filtros.
     *
     * @param query consulta validada
     * @return total de voos
     */
    public long count(FlightQuery query) {
        return params(jdbc.sql("SELECT count(*) FROM flight f WHERE " + where(query)), query)
                .query(Long.class).single();
    }

    /**
     * Busca uma página de voos.
     *
     * @param query consulta validada
     * @return voos da página, na ordem solicitada
     */
    public List<FlightView> page(FlightQuery query) {
        String sql = """
                SELECT f.airline_icao, f.flight_number, f.origin_icao, f.destination_icao, f.scheduled_departure,
                    f.actual_departure, f.scheduled_arrival, f.actual_arrival, f.status, %s AS delay
                FROM flight f
                WHERE %s
                ORDER BY %s %s NULLS LAST, f.id ASC
                LIMIT :limit OFFSET :offset
                """.formatted(SortField.DELAY.sqlExpression(), where(query), query.sort().sqlExpression(),
                query.ascending() ? "ASC" : "DESC");
        return params(jdbc.sql(sql), query)
                .param("limit", query.size())
                .param("offset", (long) query.page() * query.size())
                .query(FlightQueryRepository::toView).list();
    }

    private static String where(FlightQuery query) {
        StringBuilder where = new StringBuilder(switch (query.direction()) {
            case DEPARTURE -> "f.origin_icao = :airport";
            case ARRIVAL -> "f.destination_icao = :airport";
            case BOTH -> "(f.origin_icao = :airport OR f.destination_icao = :airport)";
        });
        if (query.from() != null) {
            where.append(" AND ").append(REFERENCE_DEPARTURE).append(" >= :fromStart");
        }
        if (query.to() != null) {
            where.append(" AND ").append(REFERENCE_DEPARTURE).append(" < :toEnd");
        }
        if (query.status() != null) {
            where.append(" AND f.status = :status");
        }
        return where.toString();
    }

    private static JdbcClient.StatementSpec params(JdbcClient.StatementSpec spec, FlightQuery query) {
        spec = spec.param("airport", query.airport());
        if (query.from() != null) {
            spec = spec.param("fromStart", query.from().atStartOfDay());
        }
        if (query.to() != null) {
            spec = spec.param("toEnd", query.to().plusDays(1).atStartOfDay());
        }
        if (query.status() != null) {
            spec = spec.param("status", query.status());
        }
        return spec;
    }

    private static FlightView toView(ResultSet rs, int row) throws SQLException {
        Object delay = rs.getObject("delay");
        return new FlightView(rs.getString("airline_icao"), rs.getString("flight_number"), rs.getString("origin_icao"),
                rs.getString("destination_icao"), rs.getObject("scheduled_departure", LocalDateTime.class),
                rs.getObject("actual_departure", LocalDateTime.class),
                rs.getObject("scheduled_arrival", LocalDateTime.class),
                rs.getObject("actual_arrival", LocalDateTime.class), rs.getString("status"),
                delay == null ? null : ((Number) delay).intValue());
    }
}
