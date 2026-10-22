package ru.itmo.serv1;

import jakarta.json.*;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.junit.Test;
import ru.itmo.serv1.resource.ApiExceptionMapper;
import ru.itmo.serv1.service.PersonService;
import ru.itmo.serv1.service.PersonValidation;

import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.ArrayList;
import java.util.HashSet;

import static org.junit.Assert.*;

public class PersonServiceTest {
    private final PersonService service = new PersonService();
    private final String body = """
            {"name":"Andrey","coordinates":{"x":10.5,"y":20},"height":178,
             "birthday":"2006-01-01","weight":83,"nationality":"SPAIN",
             "hairColor":"BLACK","eyeColor":"BLUE","location":{"x":10,"y":20,"name":"SPb"}}
            """;

    private JsonObject input(String text) {
        return PersonValidation.input(text);
    }

    private MultivaluedHashMap<String, String> query(String... pairs) {
        MultivaluedHashMap<String, String> query = new MultivaluedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) query.add(pairs[i], pairs[i + 1]);
        return query;
    }

    @Test
    public void crudKeepsServerFieldsAndReplacesOptionalFields() {
        JsonObject created = service.create(input(body));
        BigInteger id = created.getJsonNumber("id").bigIntegerValue();
        OffsetDateTime.parse(created.getString("creationDate"));
        assertEquals(created, service.get(id));
        JsonObject updated = service.update(id, input(body.replace("Andrey", "Anna")
                .replace("\"weight\":83,", "").replace("\"birthday\":\"2006-01-01\",", "")));
        assertEquals("Anna", updated.getString("name"));
        assertEquals(created.getString("creationDate"), updated.getString("creationDate"));
        assertTrue(updated.isNull("weight"));
        assertTrue(updated.isNull("birthday"));
        service.delete(id);
        assertThrows(NotFoundException.class, () -> service.get(id));
        assertThrows(NotFoundException.class, () -> service.update(id, input(body)));
        assertThrows(NotFoundException.class, () -> service.delete(id));
        assertNotEquals(created.get("id"), service.create(input(body)).get("id"));
    }

    @Test
    public void validatesJsonTypesRequiredFieldsAndConstraints() {
        for (String invalid : List.of("", "null", "[]", "{", body + "{}", body + " garbage", body.replace("178", "0"),
                body.replace("178", "1.2"), body.replace("178", "\"178\""),
                body.replace("Andrey", ""), body.replace("SPAIN", "USA"), body.replace("BLACK", "PINK"),
                body.replace("2006-01-01", "2006-02-30"), body.replace("2006-01-01", "06-01-01"),
                body.replace("\"name\":\"Andrey\",", ""), body.replace("\"x\":10.5", "\"x\":null"),
                body.replace("\"y\":20", "\"y\":1.2"), body.replace("\"weight\":83", "\"weight\":-1"),
                body.replace("\"SPb\"", "\"\""), body.replace("\"nationality\":\"SPAIN\"", "\"nationality\":null"),
                body.replace("{\"name\"", "{\"id\":1,\"name\""),
                body.replace("{\"name\"", "{\"creationDate\":null,\"name\""))) {
            assertThrows(invalid, BadRequestException.class, () -> input(invalid));
        }
    }

    @Test
    public void supportsNullsAndLargeIntegersWithoutRounding() {
        JsonObject fields = input(body.replace("\"weight\":83", "\"weight\":null")
                .replace("\"birthday\":\"2006-01-01\"", "\"birthday\":null")
                .replace("\"height\":178", "\"height\":123456789012345678901234567890"));
        JsonObject created = service.create(fields);
        assertEquals(new BigInteger("123456789012345678901234567890"), created.getJsonNumber("height").bigIntegerValue());
        assertEquals(1, service.list(query("weight", "null", "birthday", "null"), null).getJsonArray("items").size());
    }

    @Test
    public void filtersEveryFieldAndCombinesFilters() {
        JsonObject created = service.create(input(body));
        String[] pairs = {"id", "1", "name", "Andrey", "coordinatesX", "10.50", "coordinatesY", "20",
                "creationDate", created.getString("creationDate"), "height", "178", "birthday", "2006-01-01",
                "weight", "83", "nationality", "SPAIN", "hairColor", "BLACK", "eyeColor", "BLUE",
                "locationX", "10", "locationY", "20", "locationName", "SPb"};
        assertEquals(1, service.list(query(pairs), null).getJsonArray("items").size());
        for (int i = 0; i < pairs.length; i += 2) {
            assertEquals(pairs[i], 1, service.list(query(pairs[i], pairs[i+1]), null).getJsonArray("items").size());
        }
        assertEquals(0, service.list(query("name", "andrey"), null).getJsonArray("items").size());
        assertEquals(0, service.list(query("name", "Andrey", "weight", "84"), null).getJsonArray("items").size());
        String utc = OffsetDateTime.parse(created.getString("creationDate")).toInstant().toString();
        assertEquals(1, service.list(query("creationDate", utc), null).getJsonArray("items").size());
    }

    @Test
    public void sortsAllFieldsAndUsesSeveralSortKeys() {
        service.create(input(body));
        service.create(input(body.replace("178", "180")));
        service.create(input(body.replace("Andrey", "Anna")));
        for (String field : PersonValidation.FIELDS) {
            assertEquals(3, service.list(query("sort", field + ",asc"), null).getJsonArray("items").size());
            assertEquals(3, service.list(query("sort", field + ",desc"), null).getJsonArray("items").size());
        }
        JsonArray sorted = service.list(query("sort", "name,asc", "sort", "height,desc"), null).getJsonArray("items");
        assertEquals(2, sorted.getJsonObject(0).getInt("id"));
        assertEquals(1, sorted.getJsonObject(1).getInt("id"));
        assertEquals(3, sorted.getJsonObject(2).getInt("id"));
    }

    @Test
    public void paginatesFilteredResultsAndHandlesEmptyPages() {
        for (int i = 0; i < 23; i++) service.create(input(body));
        JsonObject first = service.list(query(), null);
        assertEquals(20, first.getJsonArray("items").size());
        assertEquals(20, first.getInt("nextoffset"));
        JsonObject last = service.list(query("offset", "20"), null);
        assertEquals(3, last.getJsonArray("items").size());
        assertTrue(last.isNull("nextoffset"));
        JsonObject empty = service.list(query("offset", "99999999999999999999999999999"), null);
        assertTrue(empty.getJsonArray("items").isEmpty());
        assertTrue(empty.isNull("nextoffset"));
        assertEquals(2, service.list(query("name", "Andrey", "limit", "2"), null).getInt("nextoffset"));
    }

    @Test
    public void validatesQueryParametersBeforeLookingAtData() {
        for (String[] invalid : new String[][]{{"limit","0"}, {"limit","101"}, {"limit","abc"},
                {"offset","-1"}, {"offset","1.5"}, {"sort","name"}, {"sort","name,ASC"},
                {"sort","location,asc"}, {"sort","id,asc,desc"}, {"birthday","2025-02-30"},
                {"nationality","INVALID"}, {"id","0"}, {"weight","1.2"}, {"name",""},
                {"creationDate","2025-01-01"}, {"creationDate","2025-01-01T00:00Z"}, {"coordinatesX","NaN"}}) {
            assertThrows(BadRequestException.class, () -> service.list(query(invalid), null));
        }
    }

    @Test
    public void specialOperationsExcludeNullsAndSearchIsCaseSensitive() {
        assertEquals("MAX_WEIGHT_OBJECT_NOT_FOUND", assertThrows(NotFoundException.class, service::maxWeight).getMessage());
        service.create(input(body.replace("\"weight\":83", "\"weight\":null")
                .replace("\"birthday\":\"2006-01-01\"", "\"birthday\":null")));
        assertThrows(NotFoundException.class, service::maxWeight);
        service.create(input(body));
        JsonObject heavy = service.create(input(body.replace("83", "90").replace("Andrey", "Anna")));
        assertEquals(heavy, service.maxWeight());
        assertEquals(2, service.countBirthday("2000-01-01").getInt("count"));
        assertEquals(0, service.countBirthday("2006-01-01").getInt("count"));
        assertThrows(BadRequestException.class, () -> service.countBirthday(null));
        assertThrows(BadRequestException.class, () -> service.countBirthday("null"));
        assertEquals(1, service.list(query("limit", "1"), "ndr").getInt("nextoffset"));
        assertEquals(0, service.list(query(), "andr").getJsonArray("items").size());
        assertEquals(1, service.list(query(), "Anna").getJsonArray("items").size());
    }

    @Test
    public void errorsHaveContractFieldsAndPreserveHeaders() {
        ApiExceptionMapper mapper = new ApiExceptionMapper();
        Response bad = mapper.toResponse(new BadRequestException("height must be greater than 0"));
        JsonObject error = (JsonObject) bad.getEntity();
        assertEquals(400, error.getInt("status"));
        assertEquals("Bad Request", error.getString("error"));
        assertEquals("INVALID_REQUEST", error.getString("code"));
        assertEquals("height must be greater than 0", error.getString("message"));
        JsonObject missing = (JsonObject) mapper.toResponse(new NotFoundException("OBJECT_NOT_FOUND:123")).getEntity();
        assertEquals("OBJECT_NOT_FOUND", missing.getString("code"));
        assertEquals("Объект Person с id 123 не найден", missing.getString("message"));
        assertEquals("Ресурс по запрошенному URL не найден на сервере",
                ((JsonObject) mapper.toResponse(new NotFoundException()).getEntity()).getString("message"));
        assertEquals("В коллекции нет объектов Person с ненулевым полем weight",
                ((JsonObject) mapper.toResponse(new NotFoundException("MAX_WEIGHT_OBJECT_NOT_FOUND")).getEntity()).getString("message"));
        assertEquals("RESOURCE_NOT_FOUND", ((JsonObject) mapper.toResponse(new NotFoundException()).getEntity()).getString("code"));
        Response method = mapper.toResponse(new NotAllowedException(Response.status(405).allow("GET", "HEAD", "OPTIONS").build()));
        assertEquals(405, method.getStatus());
        assertTrue(method.getHeaderString("Allow").contains("GET"));
        assertEquals("METHOD_NOT_ALLOWED", ((JsonObject) method.getEntity()).getString("code"));
        assertEquals("HTTP-метод не поддерживается для данного маршрута", ((JsonObject) method.getEntity()).getString("message"));
        Response unavailable = mapper.toResponse(new ServiceUnavailableException(60L));
        assertEquals(503, unavailable.getStatus());
        assertEquals("Сервис временно недоступен. Повторите запрос позже.", ((JsonObject) unavailable.getEntity()).getString("message"));
        assertEquals("60", unavailable.getHeaderString("Retry-After"));
        Response internal = mapper.toResponse(new IllegalStateException("secret"));
        assertEquals(500, internal.getStatus());
        assertEquals("Внутренняя ошибка сервера", ((JsonObject) internal.getEntity()).getString("message"));
        assertFalse(internal.getEntity().toString().contains("secret"));
    }

    @Test
    public void concurrentCreatesHaveUniqueIds() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<BigInteger>> jobs = new ArrayList<>();
            for (int i = 0; i < 100; i++) jobs.add(() -> service.create(input(body)).getJsonNumber("id").bigIntegerValue());
            var ids = new HashSet<BigInteger>();
            for (var result : pool.invokeAll(jobs)) ids.add(result.get());
            assertEquals(100, ids.size());
        } finally {
            pool.shutdownNow();
        }
    }
}
