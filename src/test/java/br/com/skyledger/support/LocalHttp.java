package br.com.skyledger.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cliente HTTP mínimo para testes contra a aplicação em porta aleatória.
 */
public final class LocalHttp {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private LocalHttp() {
    }

    /**
     * Envia uma requisição para {@code http://localhost:<port><path>}.
     *
     * @param method método HTTP
     * @param port porta local da aplicação
     * @param path caminho, começando com {@code /}
     * @return resposta com o corpo como texto
     * @throws UncheckedIOException se a requisição falhar por I/O
     * @throws IllegalStateException se a thread for interrompida
     */
    public static HttpResponse<String> send(String method, int port, String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Envia um {@code GET}.
     *
     * @param port porta local da aplicação
     * @param path caminho, começando com {@code /}
     * @return resposta com o corpo como texto
     */
    public static HttpResponse<String> get(int port, String path) {
        return send("GET", port, path);
    }

    /**
     * Converte o corpo de uma resposta em JSON.
     *
     * @param response resposta com corpo JSON
     * @return árvore JSON do corpo
     */
    public static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }
}
