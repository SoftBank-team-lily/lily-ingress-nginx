package com.lily.router.route;

import com.lily.router.route.RouteExceptions.RouteConflictException;
import com.lily.router.route.RouteExceptions.RouteNotFoundException;
import com.lily.router.route.dto.response.ErrorResponse;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/** 오류 응답은 모두 {@link ErrorResponse}. 코드 목록은 docs/api/01-Ingress Nginx API.md "오류" */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(RouteNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(RouteNotFoundException e) {
        return body(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(RouteConflictException.class)
    ResponseEntity<ErrorResponse> conflict(RouteConflictException e) {
        return body(HttpStatus.CONFLICT, "HOST_CONFLICT", e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> badRequest(Exception e) {
        String message = e instanceof MethodArgumentNotValidException invalid
                ? invalid.getBindingResult().getFieldErrors().stream()
                        .map(error -> error.getField() + ": " + error.getDefaultMessage())
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("요청 형식이 아니다")
                : e.getMessage();
        return body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    /** admission webhook 거부(같은 호스트·경로)는 409, 그 외 클러스터 오류는 502 */
    @ExceptionHandler(KubernetesClientException.class)
    ResponseEntity<ErrorResponse> kubernetes(KubernetesClientException e) {
        String message = String.valueOf(e.getMessage());
        if (message.contains("already defined")) {
            return body(HttpStatus.CONFLICT, "HOST_CONFLICT", message);
        }
        log.error("kubernetes api failed. code={} message={}", e.getCode(), message, e);
        return body(HttpStatus.BAD_GATEWAY, "KUBERNETES_ERROR", message);
    }

    private static ResponseEntity<ErrorResponse> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message));
    }
}
