package br.com.skyledger.ingestion.persistence;

import br.com.skyledger.ingestion.parser.VraRecord;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Gravação dos voos do VRA via tabela temporária de staging.
 *
 * <p>Todos os métodos precisam rodar na mesma transação: a staging é
 * {@code ON COMMIT DROP} e só existe na conexão da transação corrente.
 */
@Repository
public class FlightRepository {

    private static final String INSERT_STAGING = """
            INSERT INTO flight_staging (airline_icao, flight_number, authorization_code, line_type, origin_icao,
                destination_icao, scheduled_departure, actual_departure, scheduled_arrival, actual_arrival,
                status, justification_code)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc acesso JDBC ligado à transação corrente
     */
    public FlightRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Cria a staging do arquivo corrente, descartada no fim da transação. */
    public void createStaging() {
        jdbc.execute("""
                CREATE TEMP TABLE flight_staging (
                    airline_icao varchar(3), flight_number varchar(10), authorization_code text, line_type text,
                    origin_icao varchar(4), destination_icao varchar(4), scheduled_departure timestamp,
                    actual_departure timestamp, scheduled_arrival timestamp, actual_arrival timestamp,
                    status text, justification_code text
                ) ON COMMIT DROP
                """);
    }

    /**
     * Grava um lote de linhas válidas na staging.
     *
     * @param records linhas válidas do VRA
     */
    public void stage(List<VraRecord> records) {
        jdbc.batchUpdate(INSERT_STAGING, records.stream().map(record -> new Object[] {
            record.airlineIcao(), record.flightNumber(), record.authorizationCode(), record.lineType(),
            record.originIcao(), record.destinationIcao(), record.scheduledDeparture(), record.actualDeparture(),
            record.scheduledArrival(), record.actualArrival(), record.status().name(), record.justificationCode()
        }).toList());
    }

    /** Cria as companhias e aeroportos da staging que ainda não existem, só com o código. */
    public void insertMissingAirlinesAndAirports() {
        jdbc.update("INSERT INTO airline (icao) SELECT DISTINCT airline_icao FROM flight_staging "
                + "ON CONFLICT DO NOTHING");
        jdbc.update("""
                INSERT INTO airport (icao)
                SELECT origin_icao FROM flight_staging UNION SELECT destination_icao FROM flight_staging
                ON CONFLICT DO NOTHING
                """);
    }

    /**
     * Insere os voos da staging que ainda não existem pela chave natural.
     *
     * <p>{@code ON CONFLICT DO NOTHING} sem alvo: a única outra unicidade é a PK
     * gerada. Linhas repetidas dentro da própria staging também caem no conflito.
     *
     * @param batchId lote que está inserindo os voos
     * @return quantidade de voos inseridos
     */
    public int insertNewFlights(long batchId) {
        return jdbc.update("""
                INSERT INTO flight (airline_icao, flight_number, authorization_code, line_type, origin_icao,
                    destination_icao, scheduled_departure, actual_departure, scheduled_arrival, actual_arrival,
                    status, justification_code, batch_id)
                SELECT airline_icao, flight_number, authorization_code, line_type, origin_icao, destination_icao,
                    scheduled_departure, actual_departure, scheduled_arrival, actual_arrival, status,
                    justification_code, ?
                FROM flight_staging
                ON CONFLICT DO NOTHING
                """, batchId);
    }
}
