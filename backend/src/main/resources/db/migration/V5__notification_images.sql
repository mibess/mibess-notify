CREATE TABLE notification_images (
  id uuid PRIMARY KEY,
  workspace_id uuid NOT NULL REFERENCES workspaces(id),
  object_key text NOT NULL UNIQUE,
  content_type varchar(20) NOT NULL,
  byte_size integer NOT NULL,
  width integer NOT NULL,
  height integer NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX notification_images_workspace ON notification_images(workspace_id, id);
