package com.evinsurance.platform.funds.api;

import com.evinsurance.platform.funds.application.ReconciliationExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequestMapping("/api/v1/funds/reconciliation") @SecurityRequirement(name="bearerAuth")
public class ReconciliationExportController {
    private final ReconciliationExportService service;
    public ReconciliationExportController(ReconciliationExportService service){this.service=service;}

    @GetMapping("/export") @Operation(summary="客服/管理员按筛选条件分页导出工单资金对账XLSX；金额保持精确十进制文本并记录审计")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="100") int size,
        @RequestParam(required=false) String status,@RequestParam(required=false) String query,
        @RequestParam(required=false) String insuranceCompany,@RequestParam(required=false) Long shopId,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to){
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=work-order-reconciliation.xlsx")
            .header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff")
            .body(service.export(page,size,status,query,insuranceCompany,shopId,from,to));
    }
}
