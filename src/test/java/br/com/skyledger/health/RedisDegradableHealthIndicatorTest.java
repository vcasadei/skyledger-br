package br.com.skyledger.health;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * Testes unitários do mapeamento de status do {@link RedisDegradableHealthIndicator}.
 */
class RedisDegradableHealthIndicatorTest {

    /** Redis disponível permanece {@code UP}. */
    @Test
    void keepsUp() {
        assertThat(new RedisDegradableHealthIndicator(() -> Health.up().build()).health().getStatus())
                .isEqualTo(Status.UP);
    }

    /** Redis indisponível vira {@code DEGRADED}. */
    @Test
    void mapsDownToDegraded() {
        assertThat(new RedisDegradableHealthIndicator(() -> Health.down().build()).health().getStatus())
                .isEqualTo(RedisDegradableHealthIndicator.DEGRADED);
    }

    /** Uma exceção do indicador de origem também resulta em {@code DEGRADED}. */
    @Test
    void mapsExceptionToDegraded() {
        RedisDegradableHealthIndicator indicator = new RedisDegradableHealthIndicator(() -> {
            throw new IllegalStateException("Redis indisponível");
        });
        assertThat(indicator.health().getStatus()).isEqualTo(RedisDegradableHealthIndicator.DEGRADED);
    }

    /** A resposta não carrega detalhes do Redis (como a versão). */
    @Test
    void dropsDetails() {
        Health health = new RedisDegradableHealthIndicator(
                () -> Health.up().withDetail("version", "8.10.2").build()).health();
        assertThat(health.getDetails()).isEmpty();
    }
}
