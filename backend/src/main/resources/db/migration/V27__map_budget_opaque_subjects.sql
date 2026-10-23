-- Labels identify authorized requests, not a hard-coded list of acceptance sites.
-- No budget is granted: exact request hashes, operation pairing, unique slots and
-- durable attempt protection remain authoritative.
ALTER TABLE map_call_budget_slot DROP CONSTRAINT map_call_budget_slot_subject_check;
ALTER TABLE map_call_budget_slot DROP CONSTRAINT map_call_budget_slot_check;
ALTER TABLE map_call_budget_slot ADD CONSTRAINT map_budget_subject_operation_check CHECK (
 (subject ~ '^GEO_[A-Z0-9][A-Z0-9_]*$' AND operation='GEOCODE')
 OR (subject ~ '^REVERSE_[A-Z0-9][A-Z0-9_]*$' AND operation='REVERSE')
 OR (subject ~ '^ROUTE_[A-Z0-9][A-Z0-9_]*$' AND operation='DRIVING')
);
