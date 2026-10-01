package br.com.skyledger.flight;

/**
 * O código ICAO consultado não existe na tabela de aeroportos.
 */
public class AirportNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Cria a exceção.
     *
     * @param icao código consultado
     */
    public AirportNotFoundException(String icao) {
        super("Aeroporto " + icao + " não encontrado");
    }
}
