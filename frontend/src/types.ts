/** Contrato de GET /flights (espelha FlightPage e FlightView do back-end). */

export type Direction = 'DEPARTURE' | 'ARRIVAL' | 'BOTH';
export type Status = 'REALIZADO' | 'CANCELADO';
export type SortField = 'departure' | 'arrival' | 'airline' | 'flightNumber' | 'delay';

/** Voo retornado pela API. Horários ISO-8601 locais, sem fuso. */
export interface FlightView {
  airline: string;
  flightNumber: string;
  origin: string;
  destination: string;
  scheduledDeparture: string | null;
  actualDeparture: string | null;
  scheduledArrival: string | null;
  actualArrival: string | null;
  status: Status;
  departureDelayMinutes: number | null;
}

/** Página de voos retornada pela API. */
export interface FlightPage {
  content: FlightView[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  sort: string;
}

/** Filtros aplicados numa busca. Campos vazios não são enviados. */
export interface Filters {
  airport: string;
  direction: Direction;
  from: string;
  to: string;
  status: Status | '';
}

/** Ordenação pedida à API. */
export interface Sort {
  field: SortField;
  ascending: boolean;
}
