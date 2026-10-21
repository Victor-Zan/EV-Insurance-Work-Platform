package com.evinsurance.platform.integration.map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class MapConfiguration {
    @Bean @ConditionalOnProperty(name="app.maps.provider",havingValue="AMAP_WEB_V1")
    MapProvider amap(@Value("${AMAP_WEB_SERVICE_KEY:}") String key,ObjectMapper json,
                     @Value("${AMAP_MAX_CALLS_PER_PROCESS:0}") int budget,
                     @Value("${AMAP_REQUESTS_PER_SECOND:2}") int rate){return new AmapMapProvider(key,json,budget,rate);}
}
