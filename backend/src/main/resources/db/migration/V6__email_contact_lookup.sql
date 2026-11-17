CREATE INDEX contacts_workspace_email ON contacts(workspace_id, lower(spec->>'email')) WHERE spec->>'email' IS NOT NULL;
