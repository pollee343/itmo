package ru.itmo.serv1.resource;

import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.LinkedHashSet;
import java.util.Set;

@Provider
public class ApiExceptionMapper implements ExceptionMapper<Exception> {
    @Override
    public Response toResponse(Exception exception) {
        int status;
        Response original = null;
        try {
            throw exception;
        } catch (WebApplicationException web) {
            original = web.getResponse();
            status = original.getStatus();
        } catch (JsonException e) {
            status = 400;
        } catch (Exception e) {
            status = 500;
        }
        String code;
        String message;
        switch (status) {
            case 400 -> {
                code = "INVALID_REQUEST";
                message = exception.getMessage();
            }
            case 404 -> {
                code = "RESOURCE_NOT_FOUND";
                message = "Ресурс по запрошенному URL не найден на сервере";
                if (exception.getMessage() != null && exception.getMessage().startsWith("OBJECT_NOT_FOUND:")) {
                    code = "OBJECT_NOT_FOUND";
                    String id = exception.getMessage().substring("OBJECT_NOT_FOUND:".length());
                    message = "Объект Person с id " + id + " не найден";
                } else if ("MAX_WEIGHT_OBJECT_NOT_FOUND".equals(exception.getMessage())) {
                    code = "MAX_WEIGHT_OBJECT_NOT_FOUND";
                    message = "В коллекции нет объектов Person с ненулевым полем weight";
                }
            }
            case 405 -> {
                code = "METHOD_NOT_ALLOWED";
                message = "HTTP-метод не поддерживается для данного маршрута";
            }
            case 503 -> {
                code = "SERVICE_UNAVAILABLE";
                message = "Сервис временно недоступен. Повторите запрос позже.";
            }
            case 415 -> {
                code = "INVALID_REQUEST";
                message = "Content-Type должен быть application/json";
            }
            case 406 -> {
                code = "INVALID_REQUEST";
                message = "Requested response format is not supported";
            }
            default -> {
                code = "INTERNAL_ERROR";
                message = "Внутренняя ошибка сервера";
            }
        }
        Response.Status reason = Response.Status.fromStatusCode(status);
        Response.ResponseBuilder result = Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(Json.createObjectBuilder()
                        .add("status", status)
                        .add("error", reason == null ? "Error" : reason.getReasonPhrase())
                        .add("code", code)
                        .add("message", message == null ? "Invalid request" : message)
                        .build());
        if (original != null) {
            if (status == 405) {
                Set<String> methods = new LinkedHashSet<>(original.getAllowedMethods());
                if (methods.contains("GET")) methods.add("HEAD");
                methods.add("OPTIONS");
                result.allow(methods);
            }
            if (original.getHeaderString("Retry-After") != null)
                result.header("Retry-After", original.getHeaderString("Retry-After"));
        }
        return result.build();
    }
}
