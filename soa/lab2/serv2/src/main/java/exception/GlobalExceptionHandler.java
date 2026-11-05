package exception;

import http.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException exception) {
        return build(
                exception.getStatus(),
                exception.getCode(),
                exception.getMessage()
        );
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound() {
        return build(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                "Ресурс по запрошенному URL не найден на сервере"
        );
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(
    ) {
        return build(
                HttpStatus.METHOD_NOT_ALLOWED,
                "METHOD_NOT_ALLOWED",
                "HTTP-метод не поддерживается для данного маршрута"
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException() {
        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Внутренняя ошибка сервера"
        );
    }

    private ResponseEntity<ErrorResponse> build(
            HttpStatus status,
            String code,
            String message
    ) {
        ErrorResponse body = new ErrorResponse(
                status.value(),
                status.getReasonPhrase(),
                code,
                message
        );

        return ResponseEntity.status(status).body(body);
    }
}
