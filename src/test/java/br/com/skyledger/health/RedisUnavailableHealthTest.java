package br.com.skyledger.health;

import static br.com.skyledger.support.LocalHttp.get;
import static br.com.skyledger.support.LocalHttp.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

/**
 * Cenário "Redis indisponível": PostgreSQL de pé e Redis falhando na conexão.
 *
 * <p>O mock é do {@link LettuceConnectionFactory}, a fábrica real da aplicação:
 * ela implementa as interfaces síncrona e reativa, e a auto-configuração reativa
 * do Redis exige as duas no mesmo bean.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RedisUnavailableHealthTest {

    @LocalServerPort
    private int port;

    @MockitoBean
    private LettuceConnectionFactory redisConnectionFactory;

    /** Redis fora marca só o componente como {@code DEGRADED}; o geral segue {@code UP}. */
    @Test
    void staysUpWithRedisDegraded() {
        given(redisConnectionFactory.getConnection())
                .willThrow(new RedisConnectionFailureException("Redis indisponível"));

        HttpResponse<String> response = get(port, "/actuator/health");
        JsonNode body = json(response);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body.path("status").asString()).isEqualTo("UP");
        assertThat(body.path("components").path("db").path("status").asString()).isEqualTo("UP");
        assertThat(body.path("components").path("redis").path("status").asString()).isEqualTo("DEGRADED");
    }
}
