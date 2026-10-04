package com.evinsurance.platform.pricing.domain;
import java.util.*;
import java.time.Instant;
public final class ImportData {
 private ImportData(){}
 public static final List<String> FIELDS=List.of("brandCode","modelCode","internalCode","partName","alias","priceType","scope","regionCode","shopCode","amount","sourceCode","effectiveFrom","effectiveTo");
 public record Error(int rowNumber,String field,String reason,String originalValue){}
 public record RawRow(int rowNumber,Map<String,String> values,List<Error> parseErrors){}
 public record Parsed(String fileName,String format,String sha256,List<RawRow> rows){}
 public record Result(int rowNumber,Map<String,String> values,List<Error> errors,String action,PriceDraft draft){}
 public record Preview(UUID id,String fileName,String source,int totalRows,int validRows,int errorRows,int duplicateRows,Instant expiresAt){}
 public static String context(String s){if(s==null)return "";return s.substring(0,Math.min(256,s.length()));}
}
