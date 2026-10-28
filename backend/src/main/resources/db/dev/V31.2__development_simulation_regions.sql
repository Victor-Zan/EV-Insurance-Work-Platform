-- Development-only Shenzhen regions required by the selected simulation
-- destination and the current Bao'an demonstration case.
DO $$
DECLARE
    actor_id BIGINT;
    shenzhen_id BIGINT;
    luohu_id BIGINT;
    school_id BIGINT;
    receiver_id BIGINT;
BEGIN
    SELECT id INTO actor_id FROM app_user WHERE username = 'dev_admin';
    SELECT id INTO shenzhen_id FROM region WHERE code = '440300' AND level = 2;

    INSERT INTO region(code, name, level, parent_id, created_by, updated_by)
    VALUES ('440303', '罗湖区', 3, shenzhen_id, actor_id, actor_id)
    ON CONFLICT (code) DO UPDATE SET
        name = EXCLUDED.name,
        parent_id = EXCLUDED.parent_id,
        enabled = TRUE,
        updated_by = actor_id,
        updated_at = CURRENT_TIMESTAMP
    RETURNING id INTO luohu_id;

    INSERT INTO region(code, name, level, parent_id, created_by, updated_by)
    VALUES ('440306', '宝安区', 3, shenzhen_id, actor_id, actor_id)
    ON CONFLICT (code) DO UPDATE SET
        name = EXCLUDED.name,
        parent_id = EXCLUDED.parent_id,
        enabled = TRUE,
        updated_by = actor_id,
        updated_at = CURRENT_TIMESTAMP;

    SELECT id INTO school_id FROM repair_shop WHERE code = 'DEV-SIM-SMS-NIGANG';
    IF school_id IS NOT NULL AND luohu_id IS NOT NULL
       AND NOT EXISTS (
           SELECT 1 FROM shop_map_location
            WHERE shop_id = school_id
              AND address_snapshot = '深圳市罗湖区泥岗西路1068号'
              AND longitude = 114.093932
              AND latitude = 22.566022
              AND source = 'SYNTHETIC_TEST') THEN
        INSERT INTO shop_map_location(
            shop_id, version, district_id, address_snapshot, longitude, latitude,
            coordinate_system, source, created_by)
        VALUES (
            school_id,
            (SELECT COALESCE(MAX(version), 0) + 1 FROM shop_map_location WHERE shop_id = school_id),
            luohu_id, '深圳市罗湖区泥岗西路1068号', 114.093932, 22.566022,
            'GCJ-02', 'SYNTHETIC_TEST', actor_id);
    END IF;

    SELECT id INTO receiver_id FROM repair_shop WHERE code = 'DEV-SHOP';
    INSERT INTO shop_service_region(shop_id, region_id, created_by)
    SELECT receiver_id, district.id, actor_id
      FROM region district
      JOIN region city ON city.id = district.parent_id AND city.level = 2 AND city.enabled
      JOIN region province ON province.id = city.parent_id AND province.level = 1 AND province.enabled
     WHERE receiver_id IS NOT NULL
       AND district.level = 3
       AND district.enabled
       AND province.code = '440000'
    ON CONFLICT (shop_id, region_id) DO NOTHING;
END $$;
