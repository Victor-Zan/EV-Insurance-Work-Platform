package com.evinsurance.platform.workorder.api;
import com.evinsurance.platform.workorder.application.TextDraftService;
import com.evinsurance.platform.foundation.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/work-orders/{id}/text-source") @PreAuthorize("hasAnyRole('ADMIN','CUSTOMER_SERVICE')")
public class TextSourceController {
    private final TextDraftService service;public TextSourceController(TextDraftService service){this.service=service;}
    @GetMapping @Operation(summary="授权回看文字建单原文、候选及当次采用快照，不覆盖后续人工修正") public ApiResponse<tools.jackson.databind.JsonNode> source(@PathVariable UUID id){return ApiResponse.success(service.source(id));}
}
