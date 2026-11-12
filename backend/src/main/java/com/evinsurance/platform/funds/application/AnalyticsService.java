package com.evinsurance.platform.funds.application;

import com.evinsurance.platform.funds.infrastructure.AnalyticsMapper;
import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.identity.domain.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsService {
    private final AnalyticsMapper mapper;
    public AnalyticsService(AnalyticsMapper mapper) { this.mapper=mapper; }

    private Map<String,Object> filter(LocalDate from,LocalDate to,Long shop,Long creator,boolean pending,int page,int size) {
        var actor=CurrentUser.require();
        if(!actor.roles().contains(Role.ADMIN)) {
            if(!actor.roles().contains(Role.CUSTOMER_SERVICE))throw ApiException.denied();
            if(creator!=null&&creator!=actor.id())throw ApiException.denied();
            creator=actor.id();
        }
        if(from==null||to==null||from.getYear()<1||to.getYear()>9998||to.isBefore(from)||ChronoUnit.DAYS.between(from,to)>3660)
            throw ApiException.invalid("请选择有效日期范围，最长十年");
        var filter=new HashMap<String,Object>();
        filter.put("start",from.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
        filter.put("end",to.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
        filter.put("shop",shop);filter.put("creator",creator);filter.put("pending",pending);
        filter.put("offset",PageResponse.offset(page,size));filter.put("size",size);
        filter.put("grain",ChronoUnit.DAYS.between(from,to)>92?"month":"day");
        return filter;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> dashboard(LocalDate from,LocalDate to,Long shop,Long creator,boolean pending,int page,int size,
        LocalDate detailFrom,LocalDate detailTo) {
        var filter=filter(from,to,shop,creator,false,page,size);
        var summary=mapper.summary(filter);
        if((detailFrom==null)!=(detailTo==null))throw ApiException.invalid("明细日期需要同时提供起止日期");
        LocalDate start=detailFrom==null?from:detailFrom;
        LocalDate end=detailTo==null?to:detailTo;
        if(start.isBefore(from)||end.isAfter(to))throw ApiException.invalid("明细日期必须位于看板日期范围内");
        var detailFilter=filter(start,end,shop,creator,pending,page,size);
        var detailSummary=mapper.summary(detailFilter);
        return Map.of("summary",summary,"grain",filter.get("grain"),"trend",completeTrend(from,to,filter),
            "details",new PageResponse<>(page,size,((Number)detailSummary.get("completed")).longValue(),mapper.details(detailFilter)));
    }

    private List<Map<String,Object>> completeTrend(LocalDate from,LocalDate to,Map<String,Object> filter) {
        var byDate=new HashMap<String,Map<String,Object>>();
        for(var row:mapper.trend(filter))byDate.put((String)row.get("bucket"),row);
        boolean monthly="month".equals(filter.get("grain"));
        var result=new ArrayList<Map<String,Object>>();
        for(LocalDate date=monthly?from.withDayOfMonth(1):from;!date.isAfter(to);date=monthly?date.plusMonths(1):date.plusDays(1)) {
            var row=byDate.get(date.toString());
            if(row==null)row=Map.of("bucket",date.toString(),"completed",0,"accounted",0,"insurance","0.00","payable","0.00","profit","0.00");
            result.add(row);
        }
        return result;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResponse<Map<String,Object>> groups(LocalDate from,LocalDate to,Long shop,Long creator,String dimension,String metric,int page,int size) {
        if(!Set.of("shop","creator").contains(dimension)||!Set.of("profit","completed").contains(metric))throw ApiException.invalid("Unknown analytics dimension or metric");
        if(dimension.equals("creator"))CurrentUser.require().requireAdmin();
        var filter=filter(from,to,shop,creator,false,page,size);
        filter.put("dimension",dimension);filter.put("metric",metric);
        return new PageResponse<>(page,size,mapper.groupCount(filter),mapper.groups(filter));
    }
}
