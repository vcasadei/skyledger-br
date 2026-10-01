import { useState } from 'react';
import { ApiError, fetchFlights } from './api';
import FlightTable from './FlightTable';
import Pagination from './Pagination';
import SearchForm from './SearchForm';
import type { Filters, FlightPage, Sort, SortField } from './types';

/** Voos por página pedidos à API. */
export const PAGE_SIZE = 20;

const DEFAULT_SORT: Sort = { field: 'departure', ascending: true };

/** Tela de busca: filtros, tabela ordenável e paginação, tudo via GET /flights. */
export default function App() {
  const [filters, setFilters] = useState<Filters | null>(null);
  const [sort, setSort] = useState<Sort>(DEFAULT_SORT);
  const [result, setResult] = useState<FlightPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function load(nextFilters: Filters, page: number, nextSort: Sort) {
    setFilters(nextFilters);
    setSort(nextSort);
    setLoading(true);
    setError(null);
    try {
      setResult(await fetchFlights(nextFilters, page, PAGE_SIZE, nextSort));
    } catch (e) {
      // Não exibir resultado anterior como se fosse da busca atual.
      setResult(null);
      setError(e instanceof ApiError ? e.message : 'Erro inesperado na busca.');
    } finally {
      setLoading(false);
    }
  }

  function changeSort(field: SortField) {
    if (!filters) {
      return;
    }
    const nextSort = { field, ascending: sort.field === field ? !sort.ascending : true };
    void load(filters, 0, nextSort);
  }

  return (
    <main>
      <h1>Busca de voos</h1>
      <SearchForm onSearch={(f) => void load(f, 0, sort)} />
      {loading && <p className="loading">Carregando…</p>}
      {error && <p className="error" role="alert">{error}</p>}
      {result && filters && (
        <section aria-label="Resultados">
          <Pagination page={result.page} totalPages={result.totalPages} totalElements={result.totalElements}
            onPage={(p) => void load(filters, p, sort)} />
          <FlightTable flights={result.content} sort={sort} onSort={changeSort} />
        </section>
      )}
    </main>
  );
}
