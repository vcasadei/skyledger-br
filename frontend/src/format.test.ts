import { describe, expect, it } from 'vitest';
import { formatDelay, formatTime, MISSING } from './format';

describe('format', () => {
  it('formata o horário só como texto, sem conversão de fuso', () => {
    expect(formatTime('2026-05-11T08:30:00')).toBe('2026-05-11 08:30');
    expect(formatTime('2026-05-31T23:59:00')).toBe('2026-05-31 23:59');
  });

  it('usa — para horário e atraso ausentes', () => {
    expect(formatTime(null)).toBe(MISSING);
    expect(formatDelay(null)).toBe(MISSING);
  });

  it('mantém o sinal do atraso', () => {
    expect(formatDelay(8)).toBe('8');
    expect(formatDelay(-8)).toBe('-8');
    expect(formatDelay(0)).toBe('0');
  });
});
