-- Provide the parent regions required by V31.2 in an isolated test schema.
INSERT INTO region(code, name, level)
VALUES ('440000', '广东省', 1)
ON CONFLICT (code) DO NOTHING;

INSERT INTO region(code, name, level, parent_id)
SELECT '440300', '深圳市', 2, id
FROM region
WHERE code = '440000' AND level = 1
ON CONFLICT (code) DO NOTHING;
