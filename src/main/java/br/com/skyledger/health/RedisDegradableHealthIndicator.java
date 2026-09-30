package br.com.skyledger.health;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.health.DataRedisHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Health do Redis que reporta {@code DEGRADED}, e nunca {@code DOWN}, quando o
 * Redis está indisponível.
 *
 * <p>O Redis é só cache: a aplicação continua funcionando sem ele, então sua
 * indisponibilidade não pode derrubar o status geral do health-check. Com
 * {@code DEGRADED} abaixo de {@code UP} em {@code management.endpoint.health.status.order},
 * a agregação permanece {@code UP} e a resposta HTTP, 200. Registrado como bean
 * {@code redis}, o que dá o nome do componente na resposta.
 */
@Component("redis")
public class RedisDegradableHealthIndicator implements HealthIndicator {

    /** Status customizado de dependência indisponível que não derruba a aplicação. */
    public static final Status DEGRADED = new Status("DEGRADED");

    private final HealthIndicator delegate;

    /**
     * Cria o indicador sobre a conexão Redis da aplicação.
     *
     * @param connectionFactory fábrica de conexões Redis configurada pelo Spring Boot
     */
    @Autowired
    public RedisDegradableHealthIndicator(RedisConnectionFactory connectionFactory) {
        this(new DataRedisHealthIndicator(connectionFactory));
    }

    /**
     * Cria o indicador sobre um indicador de Redis já construído.
     *
     * @param delegate indicador que efetivamente consulta o Redis
     */
    RedisDegradableHealthIndicator(HealthIndicator delegate) {
        this.delegate = delegate;
    }

    /**
     * Consulta o Redis e troca {@code DOWN} por {@code DEGRADED}.
     *
     * @return saúde do Redis, sem detalhes
     */
    @Override
    public Health health() {
        Health health;
        try {
            health = delegate.health();
        } catch (RuntimeException ex) {
            return Health.status(DEGRADED).build();
        }
        if (health == null || Status.DOWN.equals(health.getStatus())) {
            return Health.status(DEGRADED).build();
        }
        return Health.status(health.getStatus()).build();
    }
}
