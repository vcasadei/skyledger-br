package br.com.skyledger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.skyledger.SkyledgerApplication;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Cobre a inicialização sem senha do PostgreSQL e a ausência da senha nos logs.
 *
 * <p>Os contextos são iniciados com o Docker Compose desligado e o banco
 * apontado para uma porta sem serviço: nenhum dos cenários precisa de conexão.
 */
@ExtendWith(OutputCaptureExtension.class)
class DatabaseCredentialsCheckTest {

    private static final String NO_DATABASE_URL = "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/skyledger";

    /**
     * Cenário "Senha ausente": um placeholder não resolvido, que é o que o Spring
     * mantém quando {@code POSTGRES_PASSWORD} não existe, interrompe a
     * inicialização com mensagem que cita a variável.
     */
    @Test
    void failsStartupWhenPasswordIsMissing() {
        assertThatThrownBy(() -> start("--spring.datasource.password=${SKYLEDGER_UNDEFINED_TEST_VARIABLE}"))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("POSTGRES_PASSWORD");
    }

    /**
     * Uma senha vazia também interrompe a inicialização.
     */
    @Test
    void failsStartupWhenPasswordIsBlank() {
        assertThatThrownBy(() -> start("--spring.datasource.password="))
                .rootCause()
                .hasMessageContaining("POSTGRES_PASSWORD");
    }

    /**
     * Cenário "Log de inicialização": o valor da senha não aparece na saída.
     *
     * @param output saída capturada durante a inicialização
     */
    @Test
    void doesNotLogPassword(CapturedOutput output) {
        String password = "pw-" + UUID.randomUUID();
        try (ConfigurableApplicationContext context = start("--spring.datasource.password=" + password)) {
            assertThat(context.isRunning()).isTrue();
        }
        assertThat(output.getAll()).contains("Tomcat started").doesNotContain(password);
    }

    private static ConfigurableApplicationContext start(String passwordArgument) {
        return SpringApplication.run(SkyledgerApplication.class,
                "--spring.docker.compose.enabled=false",
                "--server.port=0",
                NO_DATABASE_URL,
                passwordArgument);
    }
}
