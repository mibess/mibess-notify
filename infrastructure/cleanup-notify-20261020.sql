-- One-time owner-authorized production reset. Business databases are out of scope.
-- 21 October 2026, 00:00 America/Sao_Paulo. Retain integration configuration.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL lock_timeout = '15s';
LOCK TABLE events, notifications, outbox, webhook_receipts IN ACCESS EXCLUSIVE MODE;
DO $$ BEGIN
  IF current_database() <> 'mibess_notify' THEN
    RAISE EXCEPTION 'Expected the Notify production database';
  END IF;
  IF (SELECT count(*) FROM applications WHERE code IN ('ARTGIAN', 'ARTGIAN_CRM')) <> 2 THEN
    RAISE EXCEPTION 'Expected both retained Artgian applications';
  END IF;
  IF EXISTS (SELECT 1 FROM notifications WHERE status IN ('PROCESSING','QUEUED','PENDING'))
    OR EXISTS (SELECT 1 FROM events WHERE status = 'ACCEPTED')
    OR EXISTS (SELECT 1 FROM outbox WHERE published_at IS NULL) THEN
    RAISE EXCEPTION 'Wait for active workers and pending messages before cleanup';
  END IF;
END $$;
CREATE TEMP TABLE old_events ON COMMIT DROP AS
  SELECT id FROM events WHERE created_at < TIMESTAMPTZ '2026-10-21 03:00:00+00';
CREATE TEMP TABLE old_notifications ON COMMIT DROP AS
  SELECT id FROM notifications WHERE created_at < TIMESTAMPTZ '2026-10-21 03:00:00+00'
    OR event_id IN (SELECT id FROM old_events);
DELETE FROM delivery_attempts WHERE notification_id IN (SELECT id FROM old_notifications);
DELETE FROM notification_timeline WHERE notification_id IN (SELECT id FROM old_notifications);
DELETE FROM notifications WHERE id IN (SELECT id FROM old_notifications);
DELETE FROM event_timeline WHERE event_id IN (SELECT id FROM old_events);
DELETE FROM outbox WHERE created_at < TIMESTAMPTZ '2026-10-21 03:00:00+00'
  OR aggregate_id IN (SELECT id FROM old_notifications) OR aggregate_id IN (SELECT id FROM old_events);
DELETE FROM events WHERE id IN (SELECT id FROM old_events);
DELETE FROM webhook_receipts WHERE received_at < TIMESTAMPTZ '2026-10-21 03:00:00+00';
DELETE FROM inbound_messages WHERE received_at < TIMESTAMPTZ '2026-10-21 03:00:00+00';
DELETE FROM audit_logs WHERE created_at < TIMESTAMPTZ '2026-10-21 03:00:00+00';
DELETE FROM api_keys WHERE status = 'REVOKED'
  OR application_id IN (SELECT id FROM applications WHERE code NOT IN ('ARTGIAN','ARTGIAN_CRM'));
DELETE FROM routing_rules WHERE spec->>'applicationId' IN
  (SELECT id::text FROM applications WHERE code NOT IN ('ARTGIAN','ARTGIAN_CRM'))
  OR code LIKE 'IMG_PREVIEW_%';
DELETE FROM templates WHERE code LIKE 'IMG_PREVIEW_%' OR code = 'evolution_acceptance';
DELETE FROM applications WHERE code NOT IN ('ARTGIAN','ARTGIAN_CRM');
UPDATE applications SET name='Artgian Studio', updated_at=now() WHERE code='ARTGIAN';
COMMIT;
SELECT 'applications' AS resource, count(*) FROM applications
UNION ALL SELECT 'events', count(*) FROM events
UNION ALL SELECT 'notifications', count(*) FROM notifications
UNION ALL SELECT 'webhook_receipts', count(*) FROM webhook_receipts
UNION ALL SELECT 'audit_logs', count(*) FROM audit_logs;
