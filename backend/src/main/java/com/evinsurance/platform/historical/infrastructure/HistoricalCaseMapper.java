package com.evinsurance.platform.historical.infrastructure;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface HistoricalCaseMapper {
    record Data(String claimNo,String ownerName,String ownerPhone,String vehiclePlate,String brand,String model,
        String accidentAddress,String faultDescription,BigDecimal latitude,BigDecimal longitude,
        BigDecimal assessmentAmount,LocalDate repairDate,String shopName,String legacyStatus,
        String faultType,String sourceName,String note) {}
    record PreviewRow(int rowNumber,String rawJson,String rowHash,String status,String errorCode,String warningCode) {}

    void insertBatch(@Param("id") UUID id,@Param("actor") long actor,@Param("name") String name,
        @Param("hash") String hash,@Param("key") String key,@Param("total") int total);
    void insertRows(@Param("id") UUID id,@Param("rows") List<PreviewRow> rows);
    Map<String,Object> batch(@Param("id") UUID id);
    Map<String,Object> batchStatus(@Param("id") UUID id);
    long batchCount();
    List<Map<String,Object>> batches(@Param("offset") int offset,@Param("size") int size);
    List<Map<String,Object>> rows(@Param("id") UUID id,@Param("offset") int offset,@Param("size") int size);
    List<Integer> pending(@Param("id") UUID id);
    Map<String,Object> lockRow(@Param("id") UUID id,@Param("number") int number);
    int setResult(@Param("id") UUID id,@Param("number") int number,@Param("status") String status,
        @Param("error") String error,@Param("archiveId") UUID archiveId);
    List<String> existingHashes(@Param("hashes") List<String> hashes);
    List<String> existingClaims(@Param("claims") List<String> claims);
    int insertArchive(@Param("id") UUID id,@Param("batch") UUID batch,@Param("number") int number,
        @Param("hash") String hash,@Param("item") Data item);
    Map<String,Object> archiveByHash(@Param("hash") String hash);
    long archiveCount(@Param("query") String query);
    List<Map<String,Object>> archives(@Param("query") String query,@Param("offset") int offset,@Param("size") int size);
    Map<String,Object> archive(@Param("id") UUID id);
}
