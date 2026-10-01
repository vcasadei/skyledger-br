import { useState, type FormEvent } from 'react';
import type { Direction, Filters, Status } from './types';

/** Filtros iniciais: aeroporto vazio, ambos os sentidos, todas as situações. */
export const EMPTY_FILTERS: Filters = { airport: '', direction: 'BOTH', from: '', to: '', status: '' };

interface Props {
  onSearch: (filters: Filters) => void;
}

/** Formulário de filtros; o aeroporto (ICAO) é obrigatório e convertido para maiúsculas. */
export default function SearchForm({ onSearch }: Props) {
  const [filters, setFilters] = useState<Filters>(EMPTY_FILTERS);
  const [airportError, setAirportError] = useState<string | null>(null);

  function submit(event: FormEvent) {
    event.preventDefault();
    const airport = filters.airport.trim().toUpperCase();
    if (!airport) {
      setAirportError('Informe o aeroporto (código ICAO, ex.: SBGR).');
      return;
    }
    setAirportError(null);
    onSearch({ ...filters, airport });
  }

  return (
    <form className="search-form" onSubmit={submit} noValidate>
      <label>
        Aeroporto (ICAO)
        <input
          name="airport"
          value={filters.airport}
          placeholder="SBGR"
          maxLength={4}
          onChange={(e) => setFilters({ ...filters, airport: e.target.value.toUpperCase() })}
          aria-invalid={airportError !== null}
          aria-describedby="airport-hint"
        />
        <small id="airport-hint">Código ICAO de 4 letras (SBGR = Guarulhos); IATA como GRU não é aceito.</small>
        {airportError && <span className="field-error" role="alert">{airportError}</span>}
      </label>
      <label>
        Sentido
        <select
          name="direction"
          value={filters.direction}
          onChange={(e) => setFilters({ ...filters, direction: e.target.value as Direction })}
        >
          <option value="BOTH">Ambos</option>
          <option value="DEPARTURE">Saídas</option>
          <option value="ARRIVAL">Chegadas</option>
        </select>
      </label>
      <label>
        Data inicial
        <input type="date" name="from" value={filters.from}
          onChange={(e) => setFilters({ ...filters, from: e.target.value })} />
      </label>
      <label>
        Data final
        <input type="date" name="to" value={filters.to}
          onChange={(e) => setFilters({ ...filters, to: e.target.value })} />
      </label>
      <label>
        Situação
        <select
          name="status"
          value={filters.status}
          onChange={(e) => setFilters({ ...filters, status: e.target.value as Status | '' })}
        >
          <option value="">Todas</option>
          <option value="REALIZADO">Realizado</option>
          <option value="CANCELADO">Cancelado</option>
        </select>
      </label>
      <button type="submit">Buscar</button>
    </form>
  );
}
