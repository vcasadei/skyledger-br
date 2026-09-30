package br.com.skyledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifica que a aplicação sobe conectada ao PostgreSQL do {@code compose.yaml}
 * existente, com a conexão fornecida pelo Docker Compose.
 */
@SpringBootTest
class SkyledgerApplicationTest {

    @Autowired
    private DataSource dataSource;

    /**
     * O contexto sobe e obtém uma conexão válida com o banco {@code skyledger}.
     *
     * @throws Exception se a conexão falhar
     */
    @Test
    void connectsToLocalPostgres() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isValid(2)).isTrue();
            assertThat(connection.getCatalog()).isEqualTo("skyledger");
        }
    }
}
