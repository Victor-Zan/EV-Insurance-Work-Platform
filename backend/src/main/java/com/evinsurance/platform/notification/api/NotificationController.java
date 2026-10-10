package com.evinsurance.platform.notification.api;
import com.evinsurance.platform.notification.application.NotificationService;
import com.evinsurance.platform.foundation.api.*;
import tools.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
@RestController @RequestMapping("/api/v1/notifications") @SecurityRequirement(name="bearerAuth")
public class NotificationController {
    private final NotificationService service;public NotificationController(NotificationService service){this.service=service;}
    @GetMapping public ApiResponse<PageResponse<JsonNode>> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.inbox(page,size));}
    @PostMapping("/{id}/read") public ApiResponse<JsonNode> read(@PathVariable long id){return ApiResponse.success(service.read(id));}
    @GetMapping("/todos") public ApiResponse<PageResponse<JsonNode>> todos(@RequestParam(required=false) UUID caseId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.todos(caseId,page,size));}
    @PostMapping("/deadlines") public ApiResponse<JsonNode> deadline(@RequestBody NotificationService.Deadline body){return ApiResponse.success(service.deadline(body));}
    @GetMapping("/deadlines/history") public ApiResponse<PageResponse<JsonNode>> history(@RequestParam String taskKey,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.history(taskKey,page,size));}
    @GetMapping("/settings") public ApiResponse<JsonNode> settings(){return ApiResponse.success(service.settings());}
}
