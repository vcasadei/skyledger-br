package br.com.skyledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Ponto de entrada da aplicação Skyledger BR.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SkyledgerApplication {

    /**
     * Inicia a aplicação Spring Boot.
     *
     * @param args argumentos de linha de comando repassados ao Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(SkyledgerApplication.class, args);
    }
}
