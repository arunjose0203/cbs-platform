-- Run as the object owner/DBA AFTER each migration. Supply owner and runtime using psql -v.
-- Pre-create distinct LOGIN roles through your secret-management process; never use owner credentials in CBS.
\set ON_ERROR_STOP on
SELECT 1 / CASE WHEN :'owner' <> :'runtime' AND EXISTS(SELECT 1 FROM pg_roles WHERE rolname=:'runtime' AND NOT rolsuper AND NOT rolcreaterole AND NOT rolcreatedb AND NOT rolreplication AND NOT rolbypassrls) AND NOT pg_has_role(:'runtime',:'owner','MEMBER') THEN 1 ELSE 0 END;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC',current_database()) \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I',current_database(),:'runtime') \gexec
SELECT format('REVOKE ALL ON SCHEMA %I FROM PUBLIC',nspname) FROM pg_namespace WHERE nspname IN ('platform','bank','cbs_identity') \gexec
SELECT format('GRANT USAGE ON SCHEMA %I TO %I',nspname,:'runtime') FROM pg_namespace WHERE nspname IN ('platform','bank','cbs_identity','public') \gexec
SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO %I',nspname,:'runtime') FROM pg_namespace WHERE nspname IN ('platform','bank') \gexec
SELECT format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO %I',nspname,:'runtime') FROM pg_namespace WHERE nspname IN ('platform','bank') \gexec
SELECT format('GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA %I TO %I',nspname,:'runtime') FROM pg_namespace WHERE nspname IN ('platform','bank') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I',:'owner',nspname,:'runtime') FROM pg_namespace WHERE nspname IN ('platform','bank') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I GRANT USAGE, SELECT ON SEQUENCES TO %I',:'owner',nspname,:'runtime') FROM pg_namespace WHERE nspname IN ('platform','bank') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I GRANT EXECUTE ON FUNCTIONS TO %I',:'owner',nspname,:'runtime') FROM pg_namespace WHERE nspname IN ('platform','bank') \gexec
SELECT format('REVOKE ALL ON TABLE %I.%I FROM %I',schemaname,tablename,:'runtime') FROM pg_tables WHERE (schemaname IN ('bank','public') AND tablename='flyway_schema_history') OR (schemaname='bank' AND tablename='installation') OR schemaname='cbs_identity' \gexec
SELECT format('GRANT SELECT ON TABLE %I.%I TO %I',schemaname,tablename,:'runtime') FROM pg_tables WHERE (schemaname IN ('bank','public') AND tablename='flyway_schema_history') OR (schemaname='bank' AND tablename='installation') OR schemaname='cbs_identity' \gexec
-- Deliberately no CREATE, TRUNCATE, REFERENCES, TRIGGER, schema ownership, sequence UPDATE or grant option.
