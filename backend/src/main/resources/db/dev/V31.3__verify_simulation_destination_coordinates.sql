-- Development-only simulation destinations, verified against Amap geocoding on
-- 2026-10-28. They remain explicitly labelled as non-partner test fixtures.
DO $$
DECLARE
    actor_id BIGINT;
    destination RECORD;
BEGIN
    SELECT id INTO actor_id FROM app_user WHERE username = 'dev_admin';

    FOR destination IN
        SELECT *
          FROM (VALUES
              ('DEV-SHOP', 114.211688::NUMERIC(10,6), 22.686535::NUMERIC(9,6)),
              ('DEV-SIM-SMS-NIGANG', 114.091196::NUMERIC(10,6), 22.566307::NUMERIC(9,6))
          ) AS verified(code, longitude, latitude)
    LOOP
        INSERT INTO shop_map_location(
            shop_id, version, district_id, address_snapshot, longitude, latitude,
            coordinate_system, source, created_by)
        SELECT shop.id,
               (SELECT COALESCE(MAX(version), 0) + 1
                  FROM shop_map_location
                 WHERE shop_id = shop.id),
               current_location.district_id,
               shop.address,
               destination.longitude,
               destination.latitude,
               'GCJ-02',
               'ADMIN_CONFIRMED_GCJ02',
               actor_id
          FROM repair_shop shop
          JOIN LATERAL (
              SELECT district_id
                FROM shop_map_location
               WHERE shop_id = shop.id
               ORDER BY version DESC
               LIMIT 1
          ) current_location ON TRUE
         WHERE shop.code = destination.code
           AND shop.enabled
           AND shop.name LIKE '【测试／非真实合作方】%';
    END LOOP;
END $$;
