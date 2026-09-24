ALTER TABLE notifications ADD COLUMN source varchar(10) NOT NULL DEFAULT 'EVENT';
ALTER TABLE notifications ADD COLUMN manual_request_id uuid;
ALTER TABLE notifications ADD COLUMN manual_request_hash char(64);
ALTER TABLE notifications ADD COLUMN created_by varchar(200);
ALTER TABLE notifications ALTER COLUMN application_id DROP NOT NULL;
ALTER TABLE notifications ALTER COLUMN event_id DROP NOT NULL;
ALTER TABLE notifications ALTER COLUMN rule_id DROP NOT NULL;
ALTER TABLE notifications ALTER COLUMN template_id DROP NOT NULL;
ALTER TABLE notifications ADD CONSTRAINT notifications_source_shape CHECK (
  (source='EVENT' AND application_id IS NOT NULL AND event_id IS NOT NULL AND rule_id IS NOT NULL AND template_id IS NOT NULL AND manual_request_id IS NULL)
  OR (source='MANUAL' AND application_id IS NULL AND event_id IS NULL AND rule_id IS NULL AND contact_id IS NOT NULL AND manual_request_id IS NOT NULL AND manual_request_hash IS NOT NULL AND created_by IS NOT NULL)
);
CREATE UNIQUE INDEX notifications_manual_request ON notifications(workspace_id,manual_request_id) WHERE manual_request_id IS NOT NULL;
