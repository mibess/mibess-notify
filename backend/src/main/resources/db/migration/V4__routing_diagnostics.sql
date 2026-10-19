-- Additive: previous image ignores these fields/tables. Existing rule.eventType is preserved.
ALTER TABLE events ADD COLUMN routing_diagnostics jsonb NOT NULL DEFAULT '{}';
CREATE TABLE event_timeline (
  id bigserial PRIMARY KEY,
  event_id uuid NOT NULL REFERENCES events(id),
  status varchar(40) NOT NULL,
  description varchar(300) NOT NULL,
  actor varchar(200),
  details jsonb NOT NULL DEFAULT '{}',
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX event_timeline_event ON event_timeline(event_id,id);
CREATE INDEX events_application_type_recent ON events(workspace_id,application_id,type,created_at DESC,id DESC);
CREATE INDEX events_no_match_recent ON events(workspace_id,application_id,created_at DESC) WHERE status='NO_MATCH';
