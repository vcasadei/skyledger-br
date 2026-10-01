import { formatDelay, formatTime } from './format';
import type { FlightView, Sort, SortField } from './types';

interface Props {
  flights: FlightView[];
  sort: Sort;
  onSort: (field: SortField) => void;
}

const STATUS_LABEL: Record<FlightView['status'], string> = { REALIZADO: 'Realizado', CANCELADO: 'Cancelado' };

/** Tabela de voos com cabeçalhos ordenáveis (a ordenação é feita pela API). */
export default function FlightTable({ flights, sort, onSort }: Props) {
  if (flights.length === 0) {
    return <p className="empty">Nenhum voo encontrado</p>;
  }

  function header(label: string, field?: SortField) {
    if (!field) {
      return <th scope="col">{label}</th>;
    }
    const active = sort.field === field;
    const ariaSort = active ? (sort.ascending ? 'ascending' : 'descending') : 'none';
    return (
      <th scope="col" aria-sort={ariaSort}>
        <button type="button" className="sort" onClick={() => onSort(field)}>
          {label}
          {active && <span aria-hidden="true">{sort.ascending ? ' ▲' : ' ▼'}</span>}
        </button>
      </th>
    );
  }

  return (
    <>
      <p className="note">Horários como publicados pela ANAC, sem conversão de fuso.</p>
      <table>
        <thead>
          <tr>
            {header('Companhia', 'airline')}
            {header('Voo', 'flightNumber')}
            {header('Origem')}
            {header('Destino')}
            {header('Partida prevista', 'departure')}
            {header('Partida real')}
            {header('Chegada prevista', 'arrival')}
            {header('Chegada real')}
            {header('Situação')}
            {header('Atraso (min)', 'delay')}
          </tr>
        </thead>
        <tbody>
          {flights.map((f) => (
            <tr key={`${f.airline}-${f.flightNumber}-${f.origin}-${f.scheduledDeparture}-${f.actualDeparture}`}>
              <td>{f.airline}</td>
              <td>{f.flightNumber}</td>
              <td>{f.origin}</td>
              <td>{f.destination}</td>
              <td>{formatTime(f.scheduledDeparture)}</td>
              <td>{formatTime(f.actualDeparture)}</td>
              <td>{formatTime(f.scheduledArrival)}</td>
              <td>{formatTime(f.actualArrival)}</td>
              <td>{STATUS_LABEL[f.status]}</td>
              <td className="number">{formatDelay(f.departureDelayMinutes)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}
