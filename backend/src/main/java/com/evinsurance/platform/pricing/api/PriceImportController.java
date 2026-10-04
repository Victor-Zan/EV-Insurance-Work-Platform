package com.evinsurance.platform.pricing.api;
import com.evinsurance.platform.pricing.application.PriceImportService;
import com.evinsurance.platform.pricing.domain.ImportData;
import com.evinsurance.platform.pricing.infrastructure.*;
import com.evinsurance.platform.foundation.api.*;
import jakarta.validation.Valid;import jakarta.validation.constraints.*;
import java.util.UUID;import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.tags.Tag;import io.swagger.v3.oas.annotations.Operation;
@RestController @RequestMapping("/api/v1/pricing/imports") @Tag(name="Price imports")
@PreAuthorize("hasRole('ADMIN')")
public class PriceImportController {
 public record Confirmation(@NotNull @AssertTrue Boolean confirmed){}
 private final PriceImportService service;public PriceImportController(PriceImportService service){this.service=service;}
 @GetMapping("/template") @Operation(summary="UTF-8 CSV header template; dates are yyyy-MM-dd text, one-sheet XLSX uses the same fields")
 public ResponseEntity<byte[]> template(){
  return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).header("Content-Disposition","attachment; filename=price-import-template.csv")
   .body((String.join(",",ImportData.FIELDS)+"\r\n").getBytes(StandardCharsets.UTF_8));
 }
 @PostMapping(value="/previews",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
 @Operation(summary="Parse XLSX/CSV, validate and preview; writes staging metadata only, not formal prices")
 public ApiResponse<ImportData.Preview> preview(@RequestPart("file") MultipartFile file){return ApiResponse.success(service.preview(file));}
 @GetMapping("/previews/{id}") public ApiResponse<PageResponse<ImportData.Result>> rows(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.previewRows(id,page,size));}
 @GetMapping("/previews/{id}/summary") public ApiResponse<ImportData.Preview> summary(@PathVariable UUID id){return ApiResponse.success(service.summary(id));}
 @PostMapping("/previews/{id}/confirm") @Operation(summary="Explicitly confirm an atomic batch; revalidation errors persist a FAILED batch with zero price writes")
 public ApiResponse<ImportBatchEntity> confirm(@PathVariable UUID id,@Valid @RequestBody Confirmation r){return ApiResponse.success(service.confirm(id));}
 @GetMapping("/batches") public ApiResponse<PageResponse<ImportBatchEntity>> batches(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.batches(page,size));}
 @GetMapping("/batches/{id}") public ApiResponse<ImportBatchEntity> batch(@PathVariable long id){return ApiResponse.success(service.batch(id));}
 @GetMapping("/batches/{id}/errors") public ApiResponse<PageResponse<ImportErrorEntity>> errors(@PathVariable long id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.errors(id,page,size));}
}
