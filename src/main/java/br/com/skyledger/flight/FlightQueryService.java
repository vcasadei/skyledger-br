package br.com.skyledger.flight;

import org.springframework.stereotype.Service;

/**
 * Executa a consulta de voos: confere o aeroporto, conta e busca a página.
 */
@Service
public class FlightQueryService {

    private final FlightQueryRepository repository;

    /**
     * Cria o serviço.
     *
     * @param repository consulta ao banco
     */
    public FlightQueryService(FlightQueryRepository repository) {
        this.repository = repository;
    }

    /**
     * Busca uma página de voos.
     *
     * @param query consulta validada
     * @return página de voos
     * @throws AirportNotFoundException se o aeroporto não existir
     */
    public FlightPage find(FlightQuery query) {
        if (!repository.airportExists(query.airport())) {
            throw new AirportNotFoundException(query.airport());
        }
        long total = repository.count(query);
        int totalPages = (int) ((total + query.size() - 1) / query.size());
        return new FlightPage(repository.page(query), query.page(), query.size(), total, totalPages,
                query.sortDescription());
    }
}
