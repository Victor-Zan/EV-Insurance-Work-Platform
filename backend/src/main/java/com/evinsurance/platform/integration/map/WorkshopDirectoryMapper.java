package com.evinsurance.platform.integration.map;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkshopDirectoryMapper {
    long count(@Param("query") String query,@Param("district") Long district);
    List<WorkshopDirectoryService.Entry> rows(@Param("query") String query,@Param("district") Long district,@Param("offset") int offset,@Param("size") int size);
    WorkshopDirectoryService.Entry find(long id);
    WorkshopDirectoryService.Entry lock(long id);
}
