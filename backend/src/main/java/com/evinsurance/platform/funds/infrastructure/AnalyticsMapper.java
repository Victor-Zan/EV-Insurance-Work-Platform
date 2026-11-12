package com.evinsurance.platform.funds.infrastructure;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AnalyticsMapper {
    Map<String,Object> summary(Map<String,Object> filter);
    List<Map<String,Object>> trend(Map<String,Object> filter);
    List<Map<String,Object>> groups(Map<String,Object> filter);
    long groupCount(Map<String,Object> filter);
    List<Map<String,Object>> details(Map<String,Object> filter);
}
