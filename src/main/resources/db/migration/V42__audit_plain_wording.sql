-- Older audit lines that only showed a raw endpoint (from background calls) are removed; real actions stay.
DELETE FROM admin_audit WHERE action = 'OTHER' AND (detail LIKE 'GET %' OR detail LIKE 'POST /api/%' OR detail LIKE 'PUT /api/%' OR detail LIKE 'DELETE /api/%' OR detail LIKE 'PATCH /api/%');
UPDATE admin_audit SET summary = 'Made a change', detail = NULL WHERE action = 'OTHER';
