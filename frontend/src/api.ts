import type { Filters, FlightPage, Sort } from './types';

/** Erro devolvido pela API (corpo application/problem+json) ou de rede. */
export class ApiError extends Error {
  readonly status: number | null;

  constructor(message: string, status: number | null) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

/** Mensagem exibida quando a API não responde. */
export const NETWORK_ERROR = 'A API não respondeu. Verifique se a aplicação está no ar.';

/** Monta a query string de GET /flights, com só os filtros preenchidos. */
export function buildQuery(filters: Filters, page: number, size: number, sort: Sort): string {
  const params = new URLSearchParams();
  params.set('airport', filters.airport);
  params.set('direction', filters.direction);
  if (filters.from) {
    params.set('from', filters.from);
  }
  if (filters.to) {
    params.set('to', filters.to);
  }
  if (filters.status) {
    params.set('status', filters.status);
  }
  params.set('page', String(page));
  params.set('size', String(size));
  params.set('sort', `${sort.field},${sort.ascending ? 'asc' : 'desc'}`);
  return params.toString();
}

/** Busca uma página de voos; lança ApiError com o detail do problema em caso de erro. */
export async function fetchFlights(filters: Filters, page: number, size: number, sort: Sort): Promise<FlightPage> {
  let response: Response;
  try {
    response = await fetch(`/flights?${buildQuery(filters, page, size, sort)}`, {
      headers: { Accept: 'application/json' },
    });
  } catch {
    throw new ApiError(NETWORK_ERROR, null);
  }
  if (!response.ok) {
    throw new ApiError(await problemDetail(response), response.status);
  }
  return (await response.json()) as FlightPage;
}

async function problemDetail(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { detail?: string; title?: string };
    return body.detail ?? body.title ?? `Erro ${response.status}`;
  } catch {
    return `Erro ${response.status}`;
  }
}
