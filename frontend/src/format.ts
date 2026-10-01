/** Marcador exibido para horários e atrasos ausentes. */
export const MISSING = '—';

/**
 * Formata um horário ISO-8601 local ("2026-05-11T08:30:00") como "2026-05-11 08:30",
 * só manipulando texto: um Date aplicaria o fuso do navegador, e os horários não têm fuso.
 */
export function formatTime(value: string | null): string {
  if (!value) {
    return MISSING;
  }
  return value.replace('T', ' ').slice(0, 16);
}

/** Formata o atraso de partida em minutos (negativo = adiantado). */
export function formatDelay(minutes: number | null): string {
  return minutes === null ? MISSING : String(minutes);
}
