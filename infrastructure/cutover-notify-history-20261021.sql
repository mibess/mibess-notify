-- ONE-TIME owner-authorized history cutover, not a retention policy.
-- Valid business data starts 2026-10-21 00:00 America/Sao_Paulo (03:00 UTC).
-- Called only by run-notify-history-cutover-20261021.sh after its verified backup.
-- An event is the aggregate root: an old event, all of its notifications, their
-- attempts/timelines/outbox records are old history even if completed after midnight.
-- Independent notifications use their own created_at. New events and independent
-- notifications are preserved. Configuration/contacts/consents/images are untouched.
\set ON_ERROR_STOP on
\set VERBOSITY verbose
BEGIN;
SET LOCAL application_name = 'notify-history-cutover-20261021';
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
SET LOCAL idle_in_transaction_session_timeout = '60s';

DO $$ BEGIN
  IF current_database() <> 'mibess_notify' THEN
    RAISE EXCEPTION 'Expected only the Notify production database';
  END IF;
  IF clock_timestamp() < TIMESTAMPTZ '2026-10-21 03:05:00+00'
     OR clock_timestamp() >= TIMESTAMPTZ '2026-10-21 03:35:00+00' THEN
    RAISE EXCEPTION 'Outside the finite 2026-10-21 03:05-03:35 UTC cutover window';
  END IF;
  IF (SELECT count(*) FROM applications) <> 2
     OR (SELECT count(*) FROM applications WHERE code IN ('ARTGIAN', 'ARTGIAN_CRM')) <> 2 THEN
    RAISE EXCEPTION 'Expected only Artgian CRM and Artgian Studio';
  END IF;
END $$;

-- Locks serialize the target snapshot and deletion with worker transactions.
-- Lock/deadlock timeouts roll back and are retried by the bounded runner.
LOCK TABLE events, notifications, outbox, webhook_receipts,
  delivery_attempts, notification_timeline, event_timeline,
  inbound_messages, audit_logs IN ACCESS EXCLUSIVE MODE;

CREATE TEMP TABLE cutover_old_events ON COMMIT DROP AS
  SELECT id FROM events WHERE created_at < TIMESTAMPTZ '2026-10-21 03:00:00+00';
CREATE UNIQUE INDEX ON cutover_old_events(id);
CREATE TEMP TABLE cutover_old_notifications ON COMMIT DROP AS
  SELECT id FROM notifications
  WHERE created_at < TIMESTAMPTZ '2026-10-21 03:00:00+00'
     OR event_id IN (SELECT id FROM cutover_old_events);
CREATE UNIQUE INDEX ON cutover_old_notifications(id);
CREATE TEMP TABLE cutover_old_receipts ON COMMIT DROP AS
  SELECT id FROM webhook_receipts
  WHERE received_at < TIMESTAMPTZ '2026-10-21 03:00:00+00';
CREATE UNIQUE INDEX ON cutover_old_receipts(id);
CREATE TEMP TABLE cutover_old_outbox ON COMMIT DROP AS
  SELECT id FROM outbox
  WHERE created_at < TIMESTAMPTZ '2026-10-21 03:00:00+00'
     OR aggregate_id IN (SELECT id FROM cutover_old_events)
     OR aggregate_id IN (SELECT id FROM cutover_old_notifications)
     OR aggregate_id IN (SELECT id FROM cutover_old_receipts);
CREATE UNIQUE INDEX ON cutover_old_outbox(id);

DO $$ BEGIN
  -- Do not remove a row while it can still produce an authorized delivery.
  -- New, unrelated work does not block the historical cleanup.
  IF EXISTS (
    SELECT 1 FROM notifications
    WHERE id IN (SELECT id FROM cutover_old_notifications)
      AND status IN ('PENDING', 'QUEUED', 'PROCESSING')
  ) OR EXISTS (
    SELECT 1 FROM events WHERE id IN (SELECT id FROM cutover_old_events)
      AND status = 'ACCEPTED'
  ) OR EXISTS (
    SELECT 1 FROM outbox WHERE id IN (SELECT id FROM cutover_old_outbox)
      AND published_at IS NULL
  ) OR EXISTS (
    SELECT 1 FROM webhook_receipts WHERE id IN (SELECT id FROM cutover_old_receipts)
      AND status = 'RECEIVED'
  ) OR EXISTS (
    SELECT 1 FROM delivery_attempts
    WHERE notification_id IN (SELECT id FROM cutover_old_notifications)
      AND status = 'PROCESSING'
  ) THEN
    RAISE EXCEPTION USING ERRCODE = '55P03',
      MESSAGE = 'CUTOVER_PENDING_OLD_WORK: wait for historical workers before cleanup';
  END IF;
END $$;

SELECT 'old_events' AS target, count(*) AS records FROM cutover_old_events
UNION ALL SELECT 'old_notifications', count(*) FROM cutover_old_notifications
UNION ALL SELECT 'old_receipts', count(*) FROM cutover_old_receipts
UNION ALL SELECT 'old_outbox', count(*) FROM cutover_old_outbox;

DELETE FROM delivery_attempts
  WHERE notification_id IN (SELECT id FROM cutover_old_notifications);
DELETE FROM notification_timeline
  WHERE notification_id IN (SELECT id FROM cutover_old_notifications);
DELETE FROM notifications WHERE id IN (SELECT id FROM cutover_old_notifications);
DELETE FROM event_timeline WHERE event_id IN (SELECT id FROM cutover_old_events);
DELETE FROM outbox WHERE id IN (SELECT id FROM cutover_old_outbox);
DELETE FROM events WHERE id IN (SELECT id FROM cutover_old_events);
DELETE FROM webhook_receipts WHERE id IN (SELECT id FROM cutover_old_receipts);
DELETE FROM inbound_messages
  WHERE received_at < TIMESTAMPTZ '2026-10-21 03:00:00+00';
DELETE FROM audit_logs
  WHERE created_at < TIMESTAMPTZ '2026-10-21 03:00:00+00';

-- These checks are inside the same locked transaction; failure rolls it all back.
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM events WHERE created_at < TIMESTAMPTZ '2026-10-21 03:00:00+00')
    OR EXISTS (SELECT 1 FROM notifications WHERE created_at < TIMESTAMPTZ '2026-10-21 03:00:00+00')
    OR EXISTS (SELECT 1 FROM webhook_receipts WHERE received_at < TIMESTAMPTZ '2026-10-21 03:00:00+00')
    OR EXISTS (SELECT 1 FROM inbound_messages WHERE received_at < TIMESTAMPTZ '2026-10-21 03:00:00+00')
    OR EXISTS (SELECT 1 FROM audit_logs WHERE created_at < TIMESTAMPTZ '2026-10-21 03:00:00+00') THEN
    RAISE EXCEPTION 'Historical rows remain; cutover rolled back';
  END IF;
END $$;
COMMIT;
SELECT 'CUTOVER_COMMITTED_20261021' AS result, clock_timestamp() AS completed_at;
