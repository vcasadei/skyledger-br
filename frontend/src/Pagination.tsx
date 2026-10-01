interface Props {
  page: number;
  totalPages: number;
  totalElements: number;
  onPage: (page: number) => void;
}

/** Total de voos, página atual e navegação anterior/próxima. */
export default function Pagination({ page, totalPages, totalElements, onPage }: Props) {
  return (
    <nav className="pagination" aria-label="Paginação">
      <span>{totalElements} {totalElements === 1 ? 'voo' : 'voos'}</span>
      <button type="button" onClick={() => onPage(page - 1)} disabled={page <= 0}>Anterior</button>
      <span>Página {totalPages === 0 ? 0 : page + 1} de {totalPages}</span>
      <button type="button" onClick={() => onPage(page + 1)} disabled={page + 1 >= totalPages}>Próxima</button>
    </nav>
  );
}
