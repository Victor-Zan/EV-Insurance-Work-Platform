package com.evinsurance.platform.assistant.api;

import com.evinsurance.platform.assistant.application.AssistantService;
import com.evinsurance.platform.assistant.infrastructure.BigModelClient.Turn;
import com.evinsurance.platform.assistant.infrastructure.AssistantModels;
import com.evinsurance.platform.foundation.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/admin/assistant")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Administrator work order assistant")
public class AssistantController {
    private final AssistantService service;
    private final ObjectMapper json;

    public AssistantController(AssistantService service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    @GetMapping("/status")
    @Operation(summary = "Check whether the administrator assistant is configured without calling the model")
    public ApiResponse<Availability> status() {
        return ApiResponse.success(new Availability(service.available(), service.modelName(), service.defaultModel(), service.modelOptions()));
    }

    @PostMapping("/chat")
    @Operation(summary = "Send a privacy-checked conversation to the selected configured model with optional read-only platform lookup. Blocked conversations never leave the platform.")
    public ApiResponse<AssistantService.Reply> chat(@Valid @RequestBody Question question) {
        return ApiResponse.success(service.answer(question.message(), question.history(), ignored -> {}, question.model()));
    }

    @PostMapping(value = "/chat/stream", produces = "application/x-ndjson")
    @Operation(summary = "Stream an event at model invocation and the final read-only assistant reply")
    public void chatStream(@Valid @RequestBody Question question, HttpServletResponse response) {
        response.setContentType("application/x-ndjson");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        var reply = service.answer(question.message(), question.history(), model -> event(response, new StreamEvent("model", model, null)), question.model());
        event(response, new StreamEvent("reply", null, ApiResponse.success(reply)));
    }

    private void event(HttpServletResponse response, StreamEvent event) {
        try {
            var out = response.getOutputStream();
            out.write((json.writeValueAsString(event) + "\n").getBytes(StandardCharsets.UTF_8));
            response.flushBuffer();
        } catch (IOException error) {
            throw new UncheckedIOException("Assistant response is closed", error);
        }
    }

    public record Availability(boolean enabled, String model, String defaultModel, List<AssistantModels.Option> models) {}
    public record Question(@NotBlank @Size(max = 4000) String message, @Size(max = 4) List<@NotNull @Valid Turn> history,
                           @Size(max = 80) String model) {
        public Question { if (history == null) history = List.of(); }
    }
    private record StreamEvent(String type, String model, ApiResponse<AssistantService.Reply> response) {}
}
