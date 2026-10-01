package br.com.skyledger.ui;

import static br.com.skyledger.support.LocalHttp.get;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Cenário "Abrir a tela": a tela de busca é servida em {@code /} a partir do JAR.
 *
 * <p>Depende do build do front-end ({@code npm run build} na fase
 * {@code generate-resources}), que grava em {@code target/classes/static}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SearchScreenTest {

    @LocalServerPort
    private int port;

    /** {@code GET /} devolve o HTML da tela, e o script referenciado também é servido. */
    @Test
    void servesSearchScreenAtRoot() {
        HttpResponse<String> page = get(port, "/");

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/html"));
        assertThat(page.body()).contains("<div id=\"root\"></div>");

        Matcher script = Pattern.compile("src=\"(/assets/[^\"]+\\.js)\"").matcher(page.body());
        assertThat(script.find()).as("script da tela no index.html").isTrue();
        assertThat(get(port, script.group(1)).statusCode()).isEqualTo(200);
    }
}
