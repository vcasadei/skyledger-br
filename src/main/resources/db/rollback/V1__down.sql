-- Desfaz V1__create_ingestion_schema.sql. Fica fora de db/migration porque a
-- edição livre do Flyway não executa migrações de undo; é aplicado manualmente
-- (e pelo teste de rollback) no schema alvo.

DROP TABLE flight;
DROP TABLE ingestion_rejection;
DROP TABLE ingestion_batch;
DROP TABLE airport;
DROP TABLE airline;

DELETE FROM flyway_schema_history WHERE version = '1';
