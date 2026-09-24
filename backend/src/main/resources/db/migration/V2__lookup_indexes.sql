CREATE UNIQUE INDEX contacts_workspace_phone ON contacts(workspace_id,(spec->>'phone')) WHERE spec ? 'phone';
CREATE INDEX channels_phone_id ON channel_connections((spec->>'phoneNumberId')) WHERE spec->>'provider'='WHATSAPP_META';
CREATE INDEX audit_workspace_created ON audit_logs(workspace_id,created_at DESC);
CREATE INDEX webhooks_workspace_received ON webhook_receipts(workspace_id,received_at DESC);
CREATE INDEX webhook_reconciliation ON webhook_receipts(received_at) WHERE status='DEFERRED';
