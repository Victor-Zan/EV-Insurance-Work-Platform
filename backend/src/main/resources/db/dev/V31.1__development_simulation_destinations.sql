-- Development-only map destinations. Schools are not repair partners and no
-- school contacts, accounts, capabilities or real cooperation claims are created.
DO $$
DECLARE
    actor_id BIGINT;
    receiver_id BIGINT;
    school_id BIGINT;
    longgang_id BIGINT;
    luohu_id BIGINT;
BEGIN
    SELECT id INTO actor_id FROM app_user WHERE username = 'dev_admin';
    SELECT id INTO longgang_id FROM region WHERE code = '440307' AND level = 3 AND enabled;
    SELECT id INTO luohu_id FROM region WHERE code = '440303' AND level = 3 AND enabled;

    UPDATE repair_shop
       SET name = '【测试／非真实合作方】香港中文大学（深圳）',
           address = '深圳市龙岗区龙翔大道2001号',
           enabled = TRUE,
           updated_by = actor_id,
           updated_at = CURRENT_TIMESTAMP
     WHERE code = 'DEV-SHOP'
     RETURNING id INTO receiver_id;

    IF receiver_id IS NOT NULL AND longgang_id IS NOT NULL THEN
        INSERT INTO shop_map_location(
            shop_id, version, district_id, address_snapshot, longitude, latitude,
            coordinate_system, source, created_by)
        VALUES (
            receiver_id,
            (SELECT COALESCE(MAX(version), 0) + 1 FROM shop_map_location WHERE shop_id = receiver_id),
            longgang_id, '深圳市龙岗区龙翔大道2001号', 114.212820, 22.688319,
            'GCJ-02', 'SYNTHETIC_TEST', actor_id);
    END IF;

    INSERT INTO repair_shop(name, code, enabled, address, created_by, updated_by)
    VALUES (
        '【测试／非真实合作方】深圳中学泥岗校区',
        'DEV-SIM-SMS-NIGANG', TRUE, '深圳市罗湖区泥岗西路1068号', actor_id, actor_id)
    ON CONFLICT (code) DO UPDATE SET
        name = EXCLUDED.name,
        enabled = TRUE,
        address = EXCLUDED.address,
        updated_by = actor_id,
        updated_at = CURRENT_TIMESTAMP
    RETURNING id INTO school_id;

    IF school_id IS NOT NULL AND luohu_id IS NOT NULL THEN
        INSERT INTO shop_map_location(
            shop_id, version, district_id, address_snapshot, longitude, latitude,
            coordinate_system, source, created_by)
        VALUES (
            school_id,
            (SELECT COALESCE(MAX(version), 0) + 1 FROM shop_map_location WHERE shop_id = school_id),
            luohu_id, '深圳市罗湖区泥岗西路1068号', 114.093932, 22.566022,
            'GCJ-02', 'SYNTHETIC_TEST', actor_id);
    END IF;

    -- SimulationDispatchService always routes the final dev assignment to
    -- DEV-SHOP. Give that receiver the currently configured Guangdong test
    -- districts without changing production or any real shop.
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
