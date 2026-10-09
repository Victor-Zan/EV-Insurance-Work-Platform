package com.evinsurance.platform.foundation.api;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log=LoggerFactory.getLogger(GlobalExceptionHandler.class);
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> business(ApiException error) {
        return ResponseEntity.status(error.status()).body(ApiResponse.failure(error.code(),error.getMessage()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException error) {
        String field=error.getBindingResult().getFieldErrors().stream().findFirst().map(e -> e.getField()).orElse("request");
        return ResponseEntity.badRequest().body(ApiResponse.failure("VALIDATION_ERROR","Invalid field: " + field));
    }
    @ExceptionHandler({ConstraintViolationException.class,MethodArgumentTypeMismatchException.class,HttpMessageNotReadableException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.web.bind.MissingRequestHeaderException.class,
        org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    public ResponseEntity<ApiResponse<Void>> malformed(Exception error) {
        return ResponseEntity.badRequest().body(ApiResponse.failure("VALIDATION_ERROR","Invalid request parameters"));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> conflict(DataIntegrityViolationException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.failure("CONFLICT","Unique or relationship constraint conflict"));
    }
    @ExceptionHandler({org.springframework.web.servlet.resource.NoResourceFoundException.class,
        org.springframework.web.servlet.NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Void>> missing(Exception error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("NOT_FOUND","Resource not found"));
    }
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> method(Exception error) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ApiResponse.failure("METHOD_NOT_ALLOWED","Request method is not supported"));
    }
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> media(Exception error) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(ApiResponse.failure("VALIDATION_ERROR","Request content type is not supported"));
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> fileLimit(org.springframework.web.multipart.MaxUploadSizeExceededException error) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ApiResponse.failure("IMPORT_LIMIT","Import file exceeds 10 MiB"));
    }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> forbidden(AccessDeniedException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.failure("FORBIDDEN","Access is denied"));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception error) {
        // Exception messages and SQL diagnostics can contain credentials or personal information.
        log.error("Unexpected request failure: {}",error.getClass().getName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.failure("INTERNAL_ERROR","An unexpected error occurred"));
    }
}
