-- Story 5.1: admin endpoints authorize via JWT role claim "PLATFORM_ADMIN" (see SecurityConfig).
-- V7's original CHECK (ADMIN, SUPER_ADMIN) predates that decision; align the column with it.
-- Defensive backfill first: no environment currently has any admin_users rows (nothing
-- provisions them yet — see deferred W87), but the constraint change would fail on any row
-- still carrying V7's old default/allowed value if one is ever created before this runs.
UPDATE admin_users SET role = 'PLATFORM_ADMIN' WHERE role = 'ADMIN';
ALTER TABLE admin_users DROP CONSTRAINT chk_admin_users_role;
ALTER TABLE admin_users ALTER COLUMN role SET DEFAULT 'PLATFORM_ADMIN';
ALTER TABLE admin_users ADD CONSTRAINT chk_admin_users_role CHECK (role IN ('PLATFORM_ADMIN', 'SUPER_ADMIN'));
