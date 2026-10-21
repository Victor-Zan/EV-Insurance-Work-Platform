package com.evinsurance.platform.integration.map;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

class MapConfigurationTest {
    private final ApplicationContextRunner context=new ApplicationContextRunner().withUserConfiguration(MapConfiguration.class,MockMapProvider.class).withBean(ObjectMapper.class,ObjectMapper::new).withBean(MapCallBudget.class,()->org.mockito.Mockito.mock(MapCallBudget.class));
    @Test void defaultIsOnlyMock(){context.run(c->{assertThat(c).hasSingleBean(MapProvider.class);assertThat(c.getBean(MapProvider.class)).isInstanceOf(MockMapProvider.class);});}
    @Test void explicitAmapWithoutKeyFailsInsteadOfFallingBack(){context.withPropertyValues("app.maps.provider=AMAP_WEB_V1").run(c->assertThat(c).hasFailed());}
    @Test void explicitAmapIsNotMockAndDefaultBudgetBlocksNetwork(){context.withPropertyValues("app.maps.provider=AMAP_WEB_V1","AMAP_WEB_SERVICE_KEY=synthetic-offline-placeholder").run(c->{assertThat(c).hasSingleBean(MapProvider.class);var p=c.getBean(MapProvider.class);assertThat(p).isInstanceOf(AmapMapProvider.class);assertThatThrownBy(()->p.locate(new MapProvider.Point(23,113))).extracting("code").isEqualTo("MAP_LOCAL_BUDGET");});}
}
