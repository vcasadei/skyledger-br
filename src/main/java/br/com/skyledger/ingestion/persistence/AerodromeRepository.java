package br.com.skyledger.ingestion.persistence;

import br.com.skyledger.ingestion.parser.AerodromeRecord;
import br.com.skyledger.ingestion.parser.ParsedLine;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Gravação do cadastro de Aeródromos Públicos via staging e upsert.
 *
 * <p>Todos os métodos precisam rodar na mesma transação (staging {@code ON COMMIT DROP}).
 */
@Repository
public class AerodromeRepository {

    private static final String INSERT_STAGING = """
            INSERT INTO aerodrome_staging (seq, icao, ciad, name, municipality, uf, latitude, longitude,
                altitude_m, registry_status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc acesso JDBC ligado à transação corrente
     */
    public AerodromeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Cria a staging do arquivo corrente, descartada no fim da transação. */
    public void createStaging() {
        jdbc.execute("""
                CREATE TEMP TABLE aerodrome_staging (
                    seq integer, icao varchar(4), ciad varchar(10), name text, municipality text, uf text,
                    latitude numeric(12,9), longitude numeric(12,9), altitude_m numeric(7,1), registry_status text
                ) ON COMMIT DROP
                """);
    }

    /**
     * Grava um lote de linhas válidas na staging. O número da linha física é a
     * ordem usada para desempatar códigos repetidos.
     *
     * @param lines linhas válidas do cadastro
     */
    public void stage(List<ParsedLine.Valid<AerodromeRecord>> lines) {
        jdbc.batchUpdate(INSERT_STAGING, lines.stream().map(line -> {
            AerodromeRecord record = line.record();
            return new Object[] {line.line(), record.icao(), record.ciad(), record.name(), record.municipality(), record.uf(),
                record.latitude(), record.longitude(), record.altitude(), record.registryStatus()};
        }).toList());
    }

    /**
     * Cria ou atualiza os aeroportos da staging. Um código repetido no arquivo
     * vale pela última ocorrência.
     *
     * @return contagem de aeroportos inseridos e atualizados
     */
    public UpsertResult upsert() {
        List<Boolean> inserted = jdbc.queryForList("""
                INSERT INTO airport (icao, ciad, name, municipality, uf, latitude, longitude, altitude_m,
                    registry_status, in_public_registry)
                SELECT DISTINCT ON (icao) icao, ciad, name, municipality, uf, latitude, longitude, altitude_m,
                    registry_status, true
                FROM aerodrome_staging
                ORDER BY icao, seq DESC
                ON CONFLICT (icao) DO UPDATE SET
                    ciad = EXCLUDED.ciad, name = EXCLUDED.name, municipality = EXCLUDED.municipality,
                    uf = EXCLUDED.uf, latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude,
                    altitude_m = EXCLUDED.altitude_m, registry_status = EXCLUDED.registry_status,
                    in_public_registry = true, updated_at = now()
                RETURNING (xmax = 0) AS inserted
                """, Boolean.class);
        int insertedCount = (int) inserted.stream().filter(Boolean::booleanValue).count();
        return new UpsertResult(insertedCount, inserted.size() - insertedCount);
    }

    /**
     * Resultado do upsert do cadastro.
     *
     * @param inserted aeroportos novos
     * @param updated  aeroportos existentes atualizados
     */
    public record UpsertResult(int inserted, int updated) {
    }
}
