-- First migration: starts Flyway's history (table flyway_schema_history) and fixes the naming rule.
-- Name: V<YYYY_MM_DD_HHMM>__<module>_<description>.sql  (CONTRIBUTING section 4)
-- Real tables (users, assets, reservations, ...) come with the entity freeze.
-- Never edit a migration that is already on main: write a new one instead.
SELECT 1;
