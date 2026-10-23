# 高德JS真实地图与派单

本功能复用派单页及后端地址/资格/路线/人工提交，不接入POI合作网点，不自动派单。使用官方在线JS API 2.0，不转存或打包供应商SDK。依据：
- https://lbs.amap.com/api/javascript-api-v2/guide/abc/load
- https://lbs.amap.com/api/javascript-api-v2/guide/abc/jscode

## 配置与入口

后台私密环境注入 AMAP_JS_API_KEY、AMAP_JS_SECURITY_CODE、AMAP_JS_ENABLED；默认开关false。Web端公开JS Key与后端AMAP_WEB_SERVICE_KEY是不同配置；jscode只由后端固定资源代理注入，禁止放入VITE_变量、JS代码、日志或Git。启动脚本先验证私密配置，再仅给Java进程注入；Vite不继承这些密钥。

客服工单详情 → 派单 → 点击“加载高德地图”。页面初始化只读取本机配置，未点击前不请求高德。SDK和底图资源会访问高德，是独立的地图资源请求，不等同于本机GEOCODE/REVERSE/DRIVING额度，不表示高德账户免费或无限。

- 未选位置时显示广东区域概览，不是车辆位置，不读取设备定位。
- 点击地图或拖动停车标记只暂选GCJ-02经纬度，不外呼逆编码。
- 先选停车城市/区县，明确点击“核验所选位置”才使用最多1次原REVERSE额度。
- 服务端检查区域树、启用状态以及返回省市区和行政码；不匹配不可确认，不从城市中心补造停车点。
- 人工确认后沿原停车位置保存与版本审计流程；事故区县保持独立。
- 地址搜索仍使用原GEOCODE接口。道路计算仍需用量确认、原资格、策略32和持久预算。
- 已计算且有有效坐标的合格网点在地图上显示编号，图标与单选列表联动。没有绘制或伪造导航轨迹。
- 最终仍点击原派单按钮，由后端重新检查资格、版本、幂等及状态。

## 资源代理

GET /api/v1/maps/js/configuration：客服读取启用/已配置标志，不返回凭据，不外呼。
POST /api/v1/maps/js/bootstrap：客服明确加载后，返回浏览器所需的公开JS Key、固定servicePath和到期时间；不返回jscode、Web服务Key或Cookie令牌正文。

短期HttpOnly、SameSite=Strict资源Cookie只限 /api/v1/maps/js-proxy，最长10分钟且不超过原JWT有效期。代理请求逐次检查账号启用/激活/绑定、角色和authVersion；仅客服，不能用于其他业务API。登出离页时组件撤销会话；失效或重启不会自动续签。

只代理GET /v4/map/styles及 /v3/vectormap，分别到固定官方webapi.amap.com及fmap01.amap.com。不提供任意URL、POI、地理或路线代理。回调名称受限，不接受调用方jscode或改换Key；拒绝跨站、重定向、超大/异常类型响应及含安全密钥的响应。不转发请求Cookie/Authorization给高德。每会话最多120个代理资源请求、每秒最多20个；这是防滥用技术限制，不是高德计费或既有业务额度。禁用自动重试，连接3秒/读取8秒/总10秒、响应4MiB。

Vite的/api代理使用配置的VITE_API_BASE_URL对应后端，确保SDK资源Cookie同源。生产须使用HTTPS、正确的受信反向代理和/api同源转发；本轮不部署或认可生产配置。

## 新增协议

POST /api/v1/maps/locations/point：{districtId,longitude,latitude}；只有客服可调用。通过原Provider.locate及REVERSE持久台账，返回原位置候选协议，仍须明确confirm，调用本身不修改案件。点击/拖动不自动调用此接口。

推荐Row增加可空point，仅返回当前合格且已成功计算路线的网点坐标，用于本次短期地图展示；不永久保存响应、没有新增迁移，其他角色无新访问权限。

## 当前验证边界

管理端189项测试及lint/typecheck/build，后端94单元+23隔离PostgreSQL集成及Flyway/JAR通过。模拟HTTP验证固定路径、敏感响应阻断、四角色、Cookie范围/过期/停用、限流、不重试、选点行政核验及不写案件；前端模拟SDK验证显式加载、完成事件、离页回收、点击不外呼、迟到响应丢弃。真实高德JS加载、Key域名/权限和真实选点效果未验证，由用户明确点击验收，不把离线结果写成真实联网成功。本轮代理新增真实高德/百度请求0。

## 地点名称搜索

客服派单页统一为“城市/区县 → 输入地址 → 搜索位置 → 核对候选并确认”，无搜索方式下拉框。完整地址与地点名称均由同一个POI搜索接口处理，不猜测分流或失败回退；输入4—80字符。后端使用官方GET https://restapi.amap.com/v5/place/text（region=区县adcode、city_limit=true、page_size=10、page_num=人工所选页），复核返回pname/cityname/adname/adcode，跨区候选不可确认。不请求联系人、照片、额外业务字段或子POI，不自动查询下一页。

POST /api/v1/maps/locations/places：{districtId,keywords,page}，页码1—20；只允许CUSTOMER_SERVICE，经现有JWT和当前账号校验。返回{page,size,hasMore,records:[{token,candidate}]}。每次搜索/翻页最多1次POI_SEARCH，失败超时计次；满页仅表示可尝试下一页，不承诺有更多结果。空页提示补充地址或地图选点，不自动扩大范围。

结果带完整名称、地址、行政区和“地点名称候选（请人工核对具体入口）”标识。可明确点击“在地图上查看”定位标记（已加载底图可能请求地图资源，但不发起新的搜索/逆编码/路线）；不默认标记第一条或确认校园中心。人工核对入口后才点击原确认位置，沿原停车保存、版本、资格及派单服务，不改事故区县，不新增合作网点。候选仅在后端内存短期保留10分钟，保留既有会话上限；超限在发出请求前阻止并提示。

新增AMAP_PLACE_BUDGET_ENABLED（默认false）、AMAP_PLACE_BUDGET_SCHEMA、AMAP_PLACE_BUDGET_RUN_ID，仅显式dev+AMAP_WEB_V1可启用。独立Flyway位置db/map-place-budget，历史表flyway_place_budget_history，不改旧业务/预算迁移。启动只建表/校验，不创建授权或调用槽位。只有可信本机操作脚本能按本次授权一次性准备10槽位；数据库保护额度不可扩展、已尝试槽位不可重置/删除、已关闭run不可恢复，重跑配置不补次数。仅保存请求摘要和时间，无明文关键词、坐标或凭据。POI接口认证/权限/供应商额度/限流错误关闭该独立POI授权，不消费或恢复其他分类额度。原进程总量防线在本机对应增加10，不代表自动新增任何其他分类授权。

官方文档：https://lbs.amap.com/api/webservice/guide/api-advanced/newpoisearch 。Web服务搜索接口的权限、费用和供应商配额仍需用户实际账户验证；JS底图成功不代表该高级搜索服务有权限。本轮用户明确自行测试，仅编译打包，未运行测试、浏览器联调或真实调用，不能将本功能标为已完成真实搜索验收。
