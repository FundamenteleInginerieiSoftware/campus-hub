-- First migration: starts Flyway's history (table flyway_schema_history) and fixes the naming rule.
-- Name: V<YYYY_MM_DD_HHMM>__<module>_<description>.sql  (handbook section 8.2)
-- Real tables (users, assets, reservations, ...) come with the entity freeze in week 3.
-- Never edit a migration that is already on main: write a new one instead.
SELECT 1;
