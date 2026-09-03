-- Runtime application role, intentionally NOT a superuser and NOT the owner of any table, so
-- PostgreSQL's row-level security (V8__create_rls_policies.sql) actually applies to it.
-- Superusers and BYPASSRLS roles always bypass RLS outright, and FORCE ROW LEVEL SECURITY does
-- nothing to change that (see W99 in the project's deferred-work log) — this role is the fix.
--
-- Flyway continues to run as the bootstrap `postgres` superuser (see docker-compose.yml's
-- SPRING_FLYWAY_* env vars) and therefore owns every table it creates. ALTER DEFAULT PRIVILEGES
-- below means every table Flyway creates from this point on automatically grants app_runtime
-- plain DML — no ownership, no DDL, no way to bypass RLS.
--
-- This script only runs on first container initialization (empty data volume) — an existing
-- local dev volume needs `docker compose down -v` (or running this file manually) to pick it up.
CREATE ROLE app_runtime WITH LOGIN PASSWORD 'app_runtime_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;

GRANT CONNECT ON DATABASE aireceptionist TO app_runtime;
GRANT USAGE ON SCHEMA public TO app_runtime;

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_runtime;
