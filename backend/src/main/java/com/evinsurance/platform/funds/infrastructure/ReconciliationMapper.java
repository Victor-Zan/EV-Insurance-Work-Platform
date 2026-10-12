package com.evinsurance.platform.funds.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ReconciliationMapper {
    record Row(UUID id,String businessNo,String claimNo,String insuranceCompany,String shopName,
        String status,Instant createdAt,BigDecimal receivableTarget,BigDecimal receivedNet,
        BigDecimal payableTarget,BigDecimal paidNet) {}

    List<Row> page(@Param("status") String status,@Param("query") String query,
        @Param("insuranceCompany") String insuranceCompany,@Param("shopId") Long shopId,
        @Param("from") Instant from,@Param("until") Instant until,
        @Param("offset") int offset,@Param("size") int size);
}
