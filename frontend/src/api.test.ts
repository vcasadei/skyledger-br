import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, buildQuery, fetchFlights, NETWORK_ERROR } from './api';
import type { Filters } from './types';

const FILTERS: Filters = { airport: 'SBGR', direction: 'BOTH', from: '', to: '', status: '' };
const SORT = { field: 'departure' as const, ascending: true };

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('buildQuery', () => {
  it('envia só os filtros preenchidos', () => {
    expect(buildQuery(FILTERS, 0, 20, SORT)).toBe('airport=SBGR&direction=BOTH&page=0&size=20&sort=departure%2Casc');
  });

  it('inclui período, situação e ordenação decrescente', () => {
    const query = new URLSearchParams(buildQuery(
      { ...FILTERS, from: '2026-05-01', to: '2026-05-31', status: 'CANCELADO' }, 2, 20,
      { field: 'delay', ascending: false }));
    expect(query.get('from')).toBe('2026-05-01');
    expect(query.get('to')).toBe('2026-05-31');
    expect(query.get('status')).toBe('CANCELADO');
    expect(query.get('page')).toBe('2');
    expect(query.get('sort')).toBe('delay,desc');
  });
});

describe('fetchFlights', () => {
  it('converte o problem+json em ApiError com o detail', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ status: 404, detail: 'Aeroporto ZZZZ não encontrado' }),
      { status: 404, headers: { 'Content-Type': 'application/problem+json' } })));

    const error = await fetchFlights({ ...FILTERS, airport: 'ZZZZ' }, 0, 20, SORT).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).message).toBe('Aeroporto ZZZZ não encontrado');
    expect((error as ApiError).status).toBe(404);
  });

  it('informa quando a API não responde', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    await expect(fetchFlights(FILTERS, 0, 20, SORT)).rejects.toThrow(NETWORK_ERROR);
  });
});
