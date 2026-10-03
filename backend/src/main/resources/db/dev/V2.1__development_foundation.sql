-- Only loaded by the explicit dev profile. Password placeholders contain BCrypt hashes.
INSERT INTO app_user(username,display_name,password_hash) VALUES
('dev_admin','开发测试管理员', '${devAdminHash}'),
('dev_customer_service','开发测试客服', '${devCustomerServiceHash}'),
('dev_repair_shop','开发测试网点账号', '${devRepairShopHash}'),
('dev_owner','开发测试车主', '${devOwnerHash}');
INSERT INTO user_role(user_id,role_id)
SELECT u.id,r.id FROM app_user u JOIN app_role r ON r.code=CASE u.username
WHEN 'dev_admin' THEN 'ADMIN' WHEN 'dev_customer_service' THEN 'CUSTOMER_SERVICE'
WHEN 'dev_repair_shop' THEN 'REPAIR_SHOP' WHEN 'dev_owner' THEN 'OWNER' END
WHERE u.username IN ('dev_admin','dev_customer_service','dev_repair_shop','dev_owner');
INSERT INTO owner_profile(user_id,display_name) SELECT id,display_name FROM app_user WHERE username='dev_owner';
INSERT INTO region(name,code,level,sort_order) VALUES ('模拟覆盖省','DEV-PROVINCE',1,0);
INSERT INTO region(parent_id,name,code,level,sort_order)
SELECT id,'模拟覆盖市','DEV-CITY',2,0 FROM region WHERE code='DEV-PROVINCE';
INSERT INTO region(parent_id,name,code,level,sort_order)
SELECT id,'模拟覆盖区','DEV-DISTRICT',3,0 FROM region WHERE code='DEV-CITY';
INSERT INTO repair_shop(name,code,contact_name,address) VALUES ('开发模拟维修网点','DEV-SHOP','模拟联系人','仅供开发测试');
INSERT INTO shop_service_region(shop_id,region_id) SELECT s.id,r.id FROM repair_shop s CROSS JOIN region r
WHERE s.code='DEV-SHOP' AND r.code IN ('DEV-CITY','DEV-DISTRICT');
INSERT INTO shop_account(user_id,shop_id) SELECT u.id,s.id FROM app_user u CROSS JOIN repair_shop s
WHERE u.username='dev_repair_shop' AND s.code='DEV-SHOP';
INSERT INTO audit_log(actor_roles,action,object_type,summary,trace_id)
VALUES ('SYSTEM','DEV_SEED','FOUNDATION','Created explicitly marked development fixtures','dev-flyway');
