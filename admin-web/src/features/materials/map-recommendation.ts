export function mapStatus(status:string):string {
  const labels:Record<string,string>={NOT_CALCULATED:'待计算道路距离',DISABLED:'网点未启用',READY:'道路距离已计算',CAPABILITY_INCOMPLETE:'能力资料未完善（不代表没有维修能力）',MODEL_NOT_MATCHED:'已配置车型不匹配',LOCATION_INCOMPLETE:'位置资料未完善',LOCATION_STALE:'地址已变化，定位已失效',OUTSIDE_GUANGDONG:'行政归属不符合广东限制',MOCK_NO_ROAD_DISTANCE:'Mock模式，不提供真实道路距离',COORDINATE_SOURCE_UNKNOWN:'坐标来源不符合真实高德要求',QUERY_TIME_BUDGET:'查询时间预算已用完，未计算',QUALIFICATION_CHANGED:'资格或位置已变化，请重新查询',MAP_NO_ROUTE:'无可行驶路线，距离未知',MAP_QUOTA:'高德额度不足',MAP_RATE_LIMIT:'高德或本机限流',MAP_CREDENTIALS:'高德凭据错误',MAP_PERMISSION:'高德接口权限不足',MAP_LOCAL_BUDGET:'本机外呼预算关闭或耗尽',MAP_TIMEOUT:'高德超时，可能已计量；未重试',MAP_NETWORK:'高德网络错误',MAP_BAD_RESPONSE:'高德返回异常'}
  return labels[status]??'距离未知，请人工处理'
}
