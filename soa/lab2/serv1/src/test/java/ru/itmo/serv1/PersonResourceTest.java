package ru.itmo.serv1;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.core.SecurityContext;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ContainerResponse;
import org.glassfish.jersey.server.ResourceConfig;
import org.junit.Before;
import org.junit.Test;
import ru.itmo.serv1.resource.ApiExceptionMapper;
import ru.itmo.serv1.resource.PersonResource;
import ru.itmo.serv1.service.PersonService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;

import static org.junit.Assert.*;

public class PersonResourceTest {
    private ApplicationHandler application;
    private byte[] responseBody;
    private final String body = """
            {"name":"Andrey","coordinates":{"x":10.5,"y":20},"height":178,
             "birthday":"2006-01-01","weight":83,"nationality":"SPAIN",
             "hairColor":"BLACK","eyeColor":"BLUE"}
            """;

    @Before
    public void setup() {
        configure(new PersonService());
    }

    private void configure(PersonService service) {
        ResourceConfig config = new ResourceConfig(PersonResource.class, ApiExceptionMapper.class);
        config.property("jersey.config.server.wadl.disableWadl", true);
        config.register(new AbstractBinder() {
            @Override
            protected void configure() {
                bind(service).to(PersonService.class);
            }
        });
        application = new ApplicationHandler(config);
    }

    private ContainerResponse request(String method, String path, String body) throws Exception {
        SecurityContext security = new SecurityContext() {
            public Principal getUserPrincipal() { return null; }
            public boolean isUserInRole(String role) { return false; }
            public boolean isSecure() { return false; }
            public String getAuthenticationScheme() { return null; }
        };
        URI base = URI.create("http://localhost/serv1/api/");
        ContainerRequest request = new ContainerRequest(base, base.resolve(path), method, security,
                new org.glassfish.jersey.internal.MapPropertiesDelegate(), application.getConfiguration());
        request.header("Accept", "application/json");
        if (body != null) {
            request.header("Content-Type", "application/json");
            request.setEntityStream(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ContainerResponse response = application.apply(request, output).get();
        responseBody = output.toByteArray();
        if (responseBody.length > 0 && response.getMediaType() != null &&
                response.getMediaType().isCompatible(jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE)) {
            try (var reader = Json.createReader(new ByteArrayInputStream(responseBody))) {
                assertEquals(response.getEntity(), reader.readValue());
            }
            if (response.getStatus() >= 400) {
                JsonObject error = json(response);
                assertEquals(java.util.Set.of("status", "error", "code", "message"), error.keySet());
                assertEquals(response.getStatus(), error.getInt("status"));
                for (String field : List.of("error", "code", "message")) {
                    assertEquals(field, JsonValue.ValueType.STRING, error.get(field).getValueType());
                }
            }
        }
        return response;
    }

    private JsonObject json(ContainerResponse response) {
        return (JsonObject) response.getEntity();
    }

    @Test
    public void allEightEndpointsReturnExpectedStatusesAndBodies() throws Exception {
        ContainerResponse created = request("POST", "persons", body);
        assertEquals(201, created.getStatus());
        assertEquals("http://localhost/serv1/api/persons/1", created.getHeaderString("Location"));
        assertEquals(200, request("GET", "persons/1", null).getStatus());
        assertEquals(1, json(request("GET", "persons?hairColor=BLACK&limit=1", null)).getJsonArray("items").size());
        assertEquals(1, json(request("GET", "persons/max-weight", null)).getInt("id"));
        assertEquals(1, json(request("GET", "persons/count-by-birthday?birthday=2000-01-01", null)).getInt("count"));
        assertEquals(1, json(request("GET", "persons/search-by-name?substring=ndr", null)).getJsonArray("items").size());
        ContainerResponse updated = request("PUT", "persons/1", body.replace("Andrey", "Anna"));
        assertEquals(200, updated.getStatus());
        assertEquals("Anna", json(updated).getString("name"));
        assertEquals(json(created).getString("creationDate"), json(updated).getString("creationDate"));
        ContainerResponse deleted = request("DELETE", "persons/1", null);
        assertEquals(204, deleted.getStatus());
        assertFalse(deleted.hasEntity());
        assertEquals("OBJECT_NOT_FOUND", json(request("GET", "persons/1", null)).getString("code"));
        assertEquals("Объект Person с id 123 не найден", json(request("GET", "persons/123", null)).getString("message"));
    }

    @Test
    public void literalSpecialRouteRejectsOtherMethodsWithAllow() throws Exception {
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            for (String path : List.of("persons/max-weight", "persons/max-weight?test=1",
                    "persons/count-by-birthday", "persons/search-by-name")) {
                for (String payload : new String[]{body, null}) {
                    ContainerResponse response = request(method, path, payload);
                    assertEquals(method, 405, response.getStatus());
                    assertEquals("METHOD_NOT_ALLOWED", json(response).getString("code"));
                    assertTrue(response.getHeaderString("Allow").contains("GET"));
                    assertTrue(response.getHeaderString("Allow").contains("HEAD"));
                    assertTrue(response.getHeaderString("Allow").contains("OPTIONS"));
                }
            }
        }
    }

    @Test
    public void invalidRequestsAndMissingResourcesHaveJsonErrors() throws Exception {
        for (String path : List.of("persons/0", "persons/abc", "persons?limit=0", "persons?sort=id,wrong",
                "persons/count-by-birthday", "persons/search-by-name", "persons/search-by-name?substring=")) {
            ContainerResponse response = request("GET", path, null);
            assertEquals(path, 400, response.getStatus());
            assertEquals("INVALID_REQUEST", json(response).getString("code"));
        }
        for (String invalid : List.of("", "null", "{", body + "{}")) {
            assertEquals(400, request("POST", "persons", invalid).getStatus());
        }
        assertEquals("RESOURCE_NOT_FOUND", json(request("GET", "unknown", null)).getString("code"));
        assertEquals("MAX_WEIGHT_OBJECT_NOT_FOUND", json(request("GET", "persons/max-weight", null)).getString("code"));
        assertEquals(200, request("OPTIONS", "persons/max-weight", null).getStatus());
    }

    @Test
    public void specialRoutesRemainSeparateWithTrailingSlashes() throws Exception {
        request("POST", "persons", body);
        assertEquals(200, request("GET", "persons/max-weight/", null).getStatus());
        assertEquals(200, request("GET", "persons/count-by-birthday/?birthday=2000-01-01", null).getStatus());
        assertEquals(200, request("GET", "persons/search-by-name/?substring=ndr", null).getStatus());
        for (String path : List.of("persons/max-weight/", "persons/count-by-birthday/", "persons/search-by-name/")) {
            for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
                assertEquals(method + " " + path, 405, request(method, path, body).getStatus());
            }
        }
    }

    @Test
    public void httpValidationAppliesToPostPutAndEveryIdOperation() throws Exception {
        request("POST", "persons", body);
        for (String method : List.of("POST", "PUT")) {
            String path = method.equals("POST") ? "persons" : "persons/1";
            for (String invalid : new String[]{null, "", "{}", "[]", "null", body.replace("178", "0"),
                    body.replace("{\"name\"", "{\"id\":1,\"name\""),
                    body.replace("{\"name\"", "{\"creationDate\":\"2026-01-01T00:00:00Z\",\"name\"")}) {
                ContainerResponse response = request(method, path, invalid);
                assertEquals(method + " " + invalid, 400, response.getStatus());
                assertEquals("INVALID_REQUEST", json(response).getString("code"));
            }
        }
        for (String method : List.of("GET", "PUT", "DELETE")) {
            for (String id : List.of("0", "-1", "1.5", "abc")) {
                assertEquals(method + " " + id, 400, request(method, "persons/" + id,
                        method.equals("PUT") ? body : null).getStatus());
            }
            ContainerResponse missing = request(method, "persons/999", method.equals("PUT") ? body : null);
            assertEquals(404, missing.getStatus());
            assertEquals("OBJECT_NOT_FOUND", json(missing).getString("code"));
            assertEquals("Объект Person с id 999 не найден", json(missing).getString("message"));
        }
        assertEquals(200, request("GET", "persons/1", null).getStatus());
        for (String path : List.of("persons/max-weightx", "persons/count-by-birthdayx", "persons/search-by-namex")) {
            assertEquals(400, request("GET", path, null).getStatus());
        }
        assertEquals(404, request("GET", "persons/1/extra", null).getStatus());
    }

    @Test
    public void jsonWireShapeMatchesPersonAndListSchemas() throws Exception {
        ContainerResponse created = request("POST", "persons", body);
        assertTrue(created.getMediaType().isCompatible(jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE));
        JsonObject person = json(created);
        assertEquals(JsonValue.ValueType.NUMBER, person.get("id").getValueType());
        assertEquals(JsonValue.ValueType.NUMBER, person.get("height").getValueType());
        assertEquals(JsonValue.ValueType.OBJECT, person.get("coordinates").getValueType());
        assertEquals(JsonValue.NULL, person.get("location"));
        java.time.OffsetDateTime.parse(person.getString("creationDate"));
        ContainerResponse list = request("GET", "persons", null);
        assertEquals(java.util.Set.of("items", "nextoffset"), json(list).keySet());
        assertEquals(JsonValue.NULL, json(list).get("nextoffset"));
        ContainerResponse count = request("GET", "persons/count-by-birthday?birthday=2000-01-01", null);
        assertEquals(java.util.Set.of("count"), json(count).keySet());
        assertEquals(1, json(count).getInt("count"));
        ContainerResponse deleted = request("DELETE", "persons/1", null);
        assertEquals(204, deleted.getStatus());
        assertEquals(0, responseBody.length);
    }

    @Test
    public void serverErrorsAreMappedForAllEightOperations() throws Exception {
        for (int status : new int[]{500, 503}) {
            RuntimeException failure = status == 500 ? new IllegalStateException("internal detail") :
                    new jakarta.ws.rs.ServiceUnavailableException(60L);
            configure(new PersonService() {
                @Override
                public JsonObject create(JsonObject fields) { throw failure; }
                @Override
                public JsonObject get(java.math.BigInteger id) { throw failure; }
                @Override
                public JsonObject update(java.math.BigInteger id, JsonObject fields) { throw failure; }
                @Override
                public void delete(java.math.BigInteger id) { throw failure; }
                @Override
                public JsonObject maxWeight() { throw failure; }
                @Override
                public JsonObject countBirthday(String birthday) { throw failure; }
                @Override
                public JsonObject list(jakarta.ws.rs.core.MultivaluedMap<String, String> query, String substring) {
                    throw failure;
                }
            });
            for (String[] operation : new String[][]{
                    {"GET", "persons"}, {"POST", "persons"}, {"GET", "persons/1"},
                    {"PUT", "persons/1"}, {"DELETE", "persons/1"}, {"GET", "persons/max-weight"},
                    {"GET", "persons/count-by-birthday?birthday=2000-01-01"},
                    {"GET", "persons/search-by-name?substring=ndr"}}) {
                ContainerResponse response = request(operation[0], operation[1],
                        operation[0].equals("POST") || operation[0].equals("PUT") ? body : null);
                assertEquals(operation[0] + " " + operation[1], status, response.getStatus());
                JsonObject error = json(response);
                assertEquals(java.util.Set.of("status", "error", "code", "message"), error.keySet());
                assertEquals(status, error.getInt("status"));
                assertEquals(status == 500 ? "INTERNAL_ERROR" : "SERVICE_UNAVAILABLE", error.getString("code"));
                assertEquals(status == 500 ? "Internal Server Error" : "Service Unavailable", error.getString("error"));
                assertEquals(status == 500 ? "Внутренняя ошибка сервера" :
                        "Сервис временно недоступен. Повторите запрос позже.", error.getString("message"));
                if (status == 503) assertEquals("60", response.getHeaderString("Retry-After"));
            }
        }
    }
}
