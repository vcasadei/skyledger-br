-- Esquema inicial: companhias, aeroportos, voos do VRA e proveniência da ingestão.

CREATE TABLE airline (
    icao       varchar(3)  PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE airport (
    icao               varchar(4)    PRIMARY KEY,
    ciad               varchar(10),
    name               text,
    municipality       text,
    -- UF como publicada no cadastro da ANAC (por extenso).
    uf                 text,
    latitude           numeric(12,9),
    longitude          numeric(12,9),
    altitude_m         numeric(7,1),
    registry_status    text          CHECK (registry_status IN ('Cadastrado', 'Interditado')),
    in_public_registry boolean       NOT NULL DEFAULT false,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    updated_at         timestamptz   NOT NULL DEFAULT now()
);

CREATE TABLE ingestion_batch (
    id                bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dataset           text        NOT NULL CHECK (dataset IN ('AERODROMOS', 'VRA')),
    file_name         text        NOT NULL,
    sha256            char(64)    NOT NULL,
    source_updated_at date,
    status            text        NOT NULL CHECK (status IN ('COMPLETED', 'REJECTED')),
    rows_read         integer     NOT NULL,
    inserted          integer     NOT NULL,
    updated           integer     NOT NULL,
    duplicates        integer     NOT NULL,
    rejected          integer     NOT NULL,
    error             text,
    started_at        timestamptz NOT NULL,
    finished_at       timestamptz NOT NULL
);

CREATE TABLE ingestion_rejection (
    id          bigint  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id    bigint  NOT NULL REFERENCES ingestion_batch (id),
    line_number integer NOT NULL,
    reason      text    NOT NULL
);

CREATE INDEX ingestion_rejection_batch_idx ON ingestion_rejection (batch_id);

CREATE TABLE flight (
    id                  bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    airline_icao        varchar(3)  NOT NULL REFERENCES airline (icao),
    flight_number       varchar(10) NOT NULL,
    authorization_code  text        NOT NULL,
    line_type           text        NOT NULL,
    origin_icao         varchar(4)  NOT NULL REFERENCES airport (icao),
    destination_icao    varchar(4)  NOT NULL REFERENCES airport (icao),
    -- Horários como publicados pela ANAC, sem conversão de fuso.
    scheduled_departure timestamp,
    actual_departure    timestamp,
    scheduled_arrival   timestamp,
    actual_arrival      timestamp,
    status              text        NOT NULL,
    justification_code  text        NOT NULL,
    batch_id            bigint      NOT NULL REFERENCES ingestion_batch (id),
    -- NULLS NOT DISTINCT: cancelados (sem partida real) e realizados sem partida
    -- prevista também não podem se repetir a cada reprocessamento.
    CONSTRAINT flight_natural_key UNIQUE NULLS NOT DISTINCT
        (airline_icao, flight_number, origin_icao, scheduled_departure, actual_departure),
    CONSTRAINT flight_status_times CHECK (
        (status = 'REALIZADO'
            AND actual_departure IS NOT NULL AND actual_arrival IS NOT NULL
            AND (scheduled_departure IS NULL) = (scheduled_arrival IS NULL))
        OR (status = 'CANCELADO'
            AND actual_departure IS NULL AND actual_arrival IS NULL
            AND scheduled_departure IS NOT NULL AND scheduled_arrival IS NOT NULL)
    )
);

CREATE INDEX flight_batch_idx ON flight (batch_id);
