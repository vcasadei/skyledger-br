import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest';
import App from './App';
import type { FlightPage, FlightView } from './types';

let fetchMock: Mock;

beforeEach(() => {
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function flight(overrides: Partial<FlightView> = {}): FlightView {
  return {
    airline: 'AZU', flightNumber: '4840', origin: 'SBGR', destination: 'SBRJ',
    scheduledDeparture: '2026-05-11T08:30:00', actualDeparture: '2026-05-11T08:38:00',
    scheduledArrival: '2026-05-11T09:30:00', actualArrival: '2026-05-11T09:36:00',
    status: 'REALIZADO', departureDelayMinutes: 8, ...overrides,
  };
}

function page(content: FlightView[], overrides: Partial<FlightPage> = {}): FlightPage {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0,
    sort: 'departure,asc', ...overrides };
}

function respond(body: unknown, status = 200) {
  fetchMock.mockResolvedValueOnce(new Response(JSON.stringify(body), {
    status, headers: { 'Content-Type': status === 200 ? 'application/json' : 'application/problem+json' } }));
}

function lastQuery(): URLSearchParams {
  const url = fetchMock.mock.calls.at(-1)![0] as string;
  return new URLSearchParams(url.split('?')[1]);
}

function search(airport: string) {
  fireEvent.change(screen.getByLabelText(/Aeroporto/), { target: { value: airport } });
  fireEvent.click(screen.getByRole('button', { name: 'Buscar' }));
}

describe('Tela de busca', () => {
  it('abre só com o formulário, sem consultar a API', () => {
    render(<App />);
    expect(screen.getByRole('button', { name: 'Buscar' })).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('Busca com filtros: envia os filtros em maiúsculas e a página 0', async () => {
    respond(page([flight()]));
    render(<App />);

    fireEvent.change(screen.getByLabelText(/Sentido/), { target: { value: 'DEPARTURE' } });
    fireEvent.change(screen.getByLabelText(/Situação/), { target: { value: 'CANCELADO' } });
    search('sbgr');

    await screen.findByRole('table');
    const query = lastQuery();
    expect(query.get('airport')).toBe('SBGR');
    expect(query.get('direction')).toBe('DEPARTURE');
    expect(query.get('status')).toBe('CANCELADO');
    expect(query.get('page')).toBe('0');
    expect(screen.getByText('4840')).toBeTruthy();
  });

  it('Busca sem aeroporto: avisa e não consulta a API', () => {
    render(<App />);
    fireEvent.click(screen.getByRole('button', { name: 'Buscar' }));

    expect(screen.getByRole('alert').textContent).toContain('Informe o aeroporto');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('Voo cancelado na tabela: — nos horários reais e no atraso', async () => {
    respond(page([flight({ status: 'CANCELADO', actualDeparture: null, actualArrival: null,
      departureDelayMinutes: null })]));
    render(<App />);
    search('SBGR');

    const row = (await screen.findAllByRole('row'))[1];
    const cells = within(row).getAllByRole('cell').map((c) => c.textContent);
    expect(cells).toEqual(['AZU', '4840', 'SBGR', 'SBRJ', '2026-05-11 08:30', '—', '2026-05-11 09:30', '—',
      'Cancelado', '—']);
    expect(screen.getByText(/sem conversão de fuso/)).toBeTruthy();
  });

  it('Busca sem resultados: mostra a mensagem no lugar da tabela', async () => {
    respond(page([]));
    render(<App />);
    search('SBGR');

    expect(await screen.findByText('Nenhum voo encontrado')).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('Navegar para a próxima página: mesmos filtros e ordenação', async () => {
    respond(page([flight()], { totalElements: 45, totalPages: 3 }));
    respond(page([flight({ flightNumber: '5000' })], { page: 1, totalElements: 45, totalPages: 3 }));
    render(<App />);
    fireEvent.change(screen.getByLabelText(/Situação/), { target: { value: 'REALIZADO' } });
    search('SBGR');
    await screen.findByText('Página 1 de 3');

    fireEvent.click(screen.getByRole('button', { name: 'Próxima' }));

    expect(await screen.findByText('Página 2 de 3')).toBeTruthy();
    expect(screen.getByText('45 voos')).toBeTruthy();
    const query = lastQuery();
    expect(query.get('page')).toBe('1');
    expect(query.get('airport')).toBe('SBGR');
    expect(query.get('status')).toBe('REALIZADO');
    expect(query.get('sort')).toBe('departure,asc');
  });

  it('Limites da paginação: Anterior na primeira e Próxima na última desabilitados', async () => {
    respond(page([flight()], { totalElements: 45, totalPages: 3 }));
    respond(page([flight()], { page: 2, totalElements: 45, totalPages: 3 }));
    render(<App />);
    search('SBGR');
    await screen.findByText('Página 1 de 3');

    expect((screen.getByRole('button', { name: 'Anterior' }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: 'Próxima' }) as HTMLButtonElement).disabled).toBe(false);

    fireEvent.click(screen.getByRole('button', { name: 'Próxima' }));
    await screen.findByText('Página 3 de 3');
    expect((screen.getByRole('button', { name: 'Próxima' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('Ordenar por atraso: crescente e depois decrescente, sempre na página 0', async () => {
    respond(page([flight()]));
    respond(page([flight()]));
    respond(page([flight()]));
    render(<App />);
    search('SBGR');
    await screen.findByRole('table');

    fireEvent.click(screen.getByRole('button', { name: /Atraso/ }));
    await waitFor(() => expect(lastQuery().get('sort')).toBe('delay,asc'));
    expect(lastQuery().get('page')).toBe('0');

    fireEvent.click(await screen.findByRole('button', { name: /Atraso/ }));
    await waitFor(() => expect(lastQuery().get('sort')).toBe('delay,desc'));
    expect(lastQuery().get('page')).toBe('0');
    await waitFor(() => expect(screen.getByRole('columnheader', { name: /Atraso/ })
      .getAttribute('aria-sort')).toBe('descending'));
  });

  it('Aeroporto inexistente: mostra o detail e nenhuma tabela, mesmo após busca anterior', async () => {
    respond(page([flight()]));
    respond({ status: 404, detail: 'Aeroporto ZZZZ não encontrado' }, 404);
    render(<App />);
    search('SBGR');
    await screen.findByRole('table');

    search('ZZZZ');

    expect(await screen.findByText('Aeroporto ZZZZ não encontrado')).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('Código IATA: mostra a mensagem da API sobre ICAO', async () => {
    respond({ status: 400, detail: 'Parâmetro airport deve ser um código ICAO de 4 caracteres' }, 400);
    render(<App />);
    search('GRU');

    expect((await screen.findByRole('alert')).textContent).toContain('ICAO de 4 caracteres');
  });

  it('Falha de rede: informa que a API não respondeu', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    render(<App />);
    search('SBGR');

    expect((await screen.findByRole('alert')).textContent).toContain('A API não respondeu');
  });
});
