package br.com.skyledger.config;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.stereotype.Component;

/**
 * Interrompe a inicialização quando a senha do PostgreSQL não foi fornecida.
 *
 * <p>O Spring Boot mantém um placeholder não resolvido ({@code ${POSTGRES_PASSWORD}})
 * como texto literal em vez de falhar, o que só apareceria como erro de
 * autenticação na primeira conexão. A checagem usa os {@link JdbcConnectionDetails}
 * efetivos, para valer tanto com as propriedades de {@code application.yaml}
 * quanto com conexões fornecidas pelo Docker Compose nos testes.
 */
@Component
public class DatabaseCredentialsCheck {

    static final String MISSING_PASSWORD_MESSAGE =
            "POSTGRES_PASSWORD não definida: exporte a variável ou crie o .env a partir de .env.example";

    /**
     * Valida a senha dos detalhes de conexão JDBC em uso.
     *
     * @param connectionDetails detalhes de conexão JDBC resolvidos pelo Spring Boot
     * @throws IllegalStateException se a senha for nula, vazia ou um placeholder não resolvido
     */
    public DatabaseCredentialsCheck(JdbcConnectionDetails connectionDetails) {
        String password = connectionDetails.getPassword();
        if (password == null || password.isBlank() || password.startsWith("${")) {
            throw new IllegalStateException(MISSING_PASSWORD_MESSAGE);
        }
    }
}
