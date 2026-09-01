-- Story 5.1: admin endpoints authorize via JWT role claim "PLATFORM_ADMIN" (see SecurityConfig).
-- V7's original CHECK (ADMIN, SUPER_ADMIN) predates that decision; align the column with it.
ALTER TABLE admin_users DROP CONSTRAINT chk_admin_users_role;
ALTER TABLE admin_users ALTER COLUMN role SET DEFAULT 'PLATFORM_ADMIN';
ALTER TABLE admin_users ADD CONSTRAINT chk_admin_users_role CHECK (role IN ('PLATFORM_ADMIN', 'SUPER_ADMIN'));
