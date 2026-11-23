-- V4: dimensions for advanced analytics (FR-10).
--
-- Country is nullable: no GeoIP provider has been selected, so the default
-- resolver reports "not available". Device/OS/browser are derived from the
-- user agent at record time. The referrer column (FR-09) now stores only the
-- referring host, never a full URL with possible query secrets.
--
-- Separate ALTER statements keep the migration portable to H2 as well as
-- PostgreSQL (H2 does not accept multiple ADD COLUMN clauses in one ALTER).

ALTER TABLE click_event ADD COLUMN country VARCHAR(64);
ALTER TABLE click_event ADD COLUMN device_type VARCHAR(32);
ALTER TABLE click_event ADD COLUMN operating_system VARCHAR(32);
ALTER TABLE click_event ADD COLUMN browser VARCHAR(32);
