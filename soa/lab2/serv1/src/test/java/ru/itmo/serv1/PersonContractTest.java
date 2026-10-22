package ru.itmo.serv1;

import jakarta.json.*;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MultivaluedHashMap;
import org.junit.Test;
import ru.itmo.serv1.service.PersonService;
import ru.itmo.serv1.service.PersonValidation;

import java.io.StringReader;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class PersonContractTest {
    private final String low = """
            {"name":"Alpha","coordinates":{"x":-10.5,"y":-20},"height":100,
             "birthday":"2000-01-01","weight":10,"nationality":"SPAIN",
             "hairColor":"BLACK","eyeColor":"BLUE","location":{"x":-10,"y":-20,"name":"Alpha"}}
            """;
    private final String high = """
            {"name":"Zeta","coordinates":{"x":20.5,"y":30},"height":200,
             "birthday":"2020-01-01","weight":20,"nationality":"VATICAN",
             "hairColor":"WHITE","eyeColor":"YELLOW","location":{"x":20,"y":30,"name":"Zeta"}}
            """;

    private JsonObject object(String body) {
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
            return reader.readObject();
        }
    }

    private JsonObject input(String body) {
        return PersonValidation.input(body);
    }

    private MultivaluedHashMap<String, String> query(String... pairs) {
        MultivaluedHashMap<String, String> query = new MultivaluedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) query.add(pairs[i], pairs[i + 1]);
        return query;
    }

    private JsonArray items(PersonService service, String... pairs) {
        return service.list(query(pairs), null).getJsonArray("items");
    }

    @Test
    public void eachRequiredPropertyMustBePresentAndNonNull() {
        for (String field : List.of("name", "coordinates", "height", "nationality", "hairColor", "eyeColor")) {
            String absent = Json.createObjectBuilder(object(low)).remove(field).build().toString();
            String nullable = Json.createObjectBuilder(object(low)).addNull(field).build().toString();
            assertThrows(field + " absent", BadRequestException.class, () -> input(absent));
            assertThrows(field + " null", BadRequestException.class, () -> input(nullable));
        }
        for (String parent : List.of("coordinates", "location")) {
            for (String axis : List.of("x", "y")) {
                for (boolean missing : new boolean[]{true, false}) {
                    JsonObjectBuilder nested = Json.createObjectBuilder(object(low).getJsonObject(parent));
                    if (missing) nested.remove(axis);
                    else nested.addNull(axis);
                    String body = Json.createObjectBuilder(object(low)).add(parent, nested).build().toString();
                    assertThrows(parent + "." + axis, BadRequestException.class, () -> input(body));
                }
            }
        }
    }

    @Test
    public void validatesEveryPropertyType() {
        JsonValue[] wrong = {JsonValue.TRUE, Json.createArrayBuilder().build(), Json.createObjectBuilder().build()};
        for (String field : List.of("name", "height", "birthday", "weight", "nationality", "hairColor", "eyeColor")) {
            for (JsonValue value : wrong) {
                String body = Json.createObjectBuilder(object(low)).add(field, value).build().toString();
                assertThrows(field, BadRequestException.class, () -> input(body));
            }
        }
        for (String field : List.of("coordinates", "location")) {
            for (JsonValue value : new JsonValue[]{JsonValue.TRUE, Json.createValue("text"), Json.createValue(1),
                    Json.createArrayBuilder().build()}) {
                String body = Json.createObjectBuilder(object(low)).add(field, value).build().toString();
                assertThrows(field, BadRequestException.class, () -> input(body));
            }
        }
    }

    @Test
    public void nestedTypesAndStringNumberConversionsAreStrict() {
        for (String field : List.of("name", "birthday", "nationality", "hairColor", "eyeColor")) {
            String body = Json.createObjectBuilder(object(low)).add(field, 1).build().toString();
            assertThrows(field, BadRequestException.class, () -> input(body));
        }
        for (String field : List.of("height", "weight")) {
            String body = Json.createObjectBuilder(object(low)).add(field, "1").build().toString();
            assertThrows(field, BadRequestException.class, () -> input(body));
        }
        for (String parent : List.of("coordinates", "location")) {
            for (String axis : List.of("x", "y")) {
                for (JsonValue value : new JsonValue[]{Json.createValue("1"), JsonValue.TRUE,
                        Json.createObjectBuilder().build(), Json.createArrayBuilder().build()}) {
                    JsonObjectBuilder nested = Json.createObjectBuilder(object(low).getJsonObject(parent)).add(axis, value);
                    String body = Json.createObjectBuilder(object(low)).add(parent, nested).build().toString();
                    assertThrows(parent + "." + axis, BadRequestException.class, () -> input(body));
                }
            }
        }
        for (JsonValue value : new JsonValue[]{Json.createValue(1), JsonValue.TRUE,
                Json.createObjectBuilder().build(), Json.createArrayBuilder().build(), Json.createValue("")}) {
            JsonObjectBuilder location = Json.createObjectBuilder(object(low).getJsonObject("location")).add("name", value);
            String body = Json.createObjectBuilder(object(low)).add("location", location).build().toString();
            assertThrows("location.name", BadRequestException.class, () -> input(body));
        }
    }

    @Test
    public void optionalPropertiesCanBeAbsentOrNull() {
        for (String field : List.of("birthday", "weight", "location")) {
            JsonObject absent = input(Json.createObjectBuilder(object(low)).remove(field).build().toString());
            JsonObject nullable = input(Json.createObjectBuilder(object(low)).addNull(field).build().toString());
            assertTrue(absent.isNull(field));
            assertTrue(nullable.isNull(field));
        }
        for (boolean missing : new boolean[]{true, false}) {
            JsonObjectBuilder location = Json.createObjectBuilder(object(low).getJsonObject("location"));
            if (missing) location.remove("name");
            else location.addNull("name");
            JsonObject result = input(Json.createObjectBuilder(object(low)).add("location", location).build().toString());
            assertTrue(result.getJsonObject("location").isNull("name"));
        }
        assertEquals(" ", input(low.replace("Alpha", " ")).getString("name"));
    }

    @Test
    public void checksAllEnumValuesAndPositiveIntegerConstraints() {
        for (String country : List.of("SPAIN", "CHINA", "VATICAN", "NORTH_KOREA")) {
            assertEquals(country, input(low.replace("SPAIN", country)).getString("nationality"));
        }
        for (String color : List.of("GREEN", "RED", "BLACK", "BLUE", "YELLOW", "WHITE", "BROWN")) {
            assertEquals(color, input(low.replace("BLACK", color)).getString("hairColor"));
            assertEquals(color, input(low.replace("BLUE", color)).getString("eyeColor"));
        }
        for (String field : List.of("height", "weight")) {
            for (int number : new int[]{0, -1}) {
                String body = Json.createObjectBuilder(object(low)).add(field, number).build().toString();
                assertThrows(field, BadRequestException.class, () -> input(body));
            }
            String body = Json.createObjectBuilder(object(low)).add(field, 1).build().toString();
            assertEquals(1, input(body).getInt(field));
        }
        assertEquals("2000-02-29", input(low.replace("2000-01-01", "2000-02-29")).getString("birthday"));
        assertThrows(BadRequestException.class, () -> input(low.replace("2000-01-01", "1900-02-29")));
    }

    @Test
    public void eachFilterExcludesNonMatchingPeople() {
        PersonService service = new PersonService();
        JsonObject created = service.create(input(low));
        service.create(input(high));
        String[] filters = {"id", "1", "name", "Alpha", "coordinatesX", "-10.50", "coordinatesY", "-20",
                "creationDate", created.getString("creationDate"), "height", "100", "birthday", "2000-01-01",
                "weight", "10", "nationality", "SPAIN", "hairColor", "BLACK", "eyeColor", "BLUE",
                "locationX", "-10", "locationY", "-20", "locationName", "Alpha"};
        for (int i = 0; i < filters.length; i += 2) {
            JsonArray result = items(service, filters[i], filters[i + 1]);
            if (!filters[i].equals("creationDate")) assertEquals(filters[i], 1, result.size());
            assertEquals(filters[i], 1, result.getJsonObject(0).getInt("id"));
        }
        assertEquals(1, items(service, filters).size());
        assertTrue(items(service, "nationality", "SPAIN", "eyeColor", "YELLOW").isEmpty());
        assertTrue(items(service, "locationName", "alpha").isEmpty());
        assertEquals(2, items(service).size());
    }

    @Test
    public void checksActualOrderForEverySortFieldAndNullableFields() {
        PersonService service = new PersonService();
        JsonObject first = service.create(input(high));
        JsonObject second = service.create(input(low));
        for (String field : PersonValidation.FIELDS) {
            int ascendingFirst = Set.of("id", "creationDate").contains(field) ? 1 : 2;
            int descendingFirst = ascendingFirst == 1 ? 2 : 1;
            if (field.equals("creationDate") && first.getString("creationDate").equals(second.getString("creationDate")))
                descendingFirst = 1;
            assertEquals(field + " asc", ascendingFirst, items(service, "sort", field + ",asc").getJsonObject(0).getInt("id"));
            assertEquals(field + " desc", descendingFirst, items(service, "sort", field + ",desc").getJsonObject(0).getInt("id"));
        }
        JsonObjectBuilder nullable = Json.createObjectBuilder(object(low)).addNull("birthday").addNull("weight").addNull("location");
        service.create(input(nullable.build().toString()));
        for (String field : List.of("birthday", "weight", "locationX", "locationY", "locationName")) {
            assertEquals(field, 3, items(service, "sort", field + ",asc").getJsonObject(0).getInt("id"));
            JsonArray descending = items(service, "sort", field + ",desc");
            assertEquals(field, 3, descending.getJsonObject(2).getInt("id"));
        }
    }

    @Test
    public void sortPriorityAndPaginationAreAppliedAfterFiltering() {
        PersonService service = new PersonService();
        service.create(input(low));
        service.create(input(low.replace("100", "150")));
        service.create(input(low.replace("100", "150")));
        service.create(input(high));
        JsonObject page = service.list(query("nationality", "SPAIN", "sort", "name,asc",
                "sort", "height,desc", "sort", "id,desc", "offset", "1", "limit", "1"), null);
        assertEquals(2, page.getJsonArray("items").getJsonObject(0).getInt("id"));
        assertEquals(2, page.getInt("nextoffset"));
        JsonObject last = service.list(query("nationality", "SPAIN", "sort", "name,asc",
                "sort", "height,desc", "sort", "id,desc", "offset", "2", "limit", "1"), null);
        assertEquals(1, last.getJsonArray("items").getJsonObject(0).getInt("id"));
        assertTrue(last.isNull("nextoffset"));
        assertEquals(100, itemsWithManyPeople().getJsonArray("items").size());
    }

    private JsonObject itemsWithManyPeople() {
        PersonService service = new PersonService();
        for (int i = 0; i < 101; i++) service.create(input(low));
        JsonObject result = service.list(query("limit", "100"), null);
        assertEquals(100, result.getInt("nextoffset"));
        return result;
    }

    @Test
    public void allInvalidFiltersAreRejectedEvenForEmptyCollection() {
        PersonService service = new PersonService();
        for (String[] filter : new String[][]{{"id", "-1"}, {"name", ""}, {"coordinatesX", "NaN"},
                {"coordinatesY", "1.5"}, {"creationDate", "2026-02-30T12:00:00Z"}, {"height", "0"},
                {"birthday", "2001-02-29"}, {"weight", "0"}, {"nationality", "spain"},
                {"hairColor", "black"}, {"eyeColor", "blue"}, {"locationX", "1.5"},
                {"locationY", "abc"}, {"locationName", ""}}) {
            assertThrows(filter[0], BadRequestException.class, () -> service.list(query(filter), null));
        }
        for (String[] parameter : new String[][]{{"limit", ""}, {"limit", "1.5"}, {"limit", "-1"},
                {"offset", ""}, {"offset", "abc"}, {"sort", ""}, {"sort", "name,asc,desc"},
                {"sort", "name,asc "}, {"sort", "name,DESC"}}) {
            assertThrows(parameter[0], BadRequestException.class, () -> service.list(query(parameter), null));
        }
    }

    @Test
    public void searchPaginationAndBirthdayBoundariesUseOnlyMatchingObjects() {
        PersonService service = new PersonService();
        service.create(input(low));
        service.create(input(high));
        service.create(input(low));
        service.create(input(low.replace("\"birthday\":\"2000-01-01\"", "\"birthday\":null")));
        JsonObject first = service.list(query("limit", "1"), "lph");
        assertEquals(1, first.getJsonArray("items").getJsonObject(0).getInt("id"));
        assertEquals(1, first.getInt("nextoffset"));
        JsonObject last = service.list(query("limit", "1", "offset", "2"), "lph");
        assertEquals(4, last.getJsonArray("items").getJsonObject(0).getInt("id"));
        assertTrue(last.isNull("nextoffset"));
        assertEquals(1, service.countBirthday("2000-01-01").getInt("count"));
        assertEquals(0, service.countBirthday("2020-01-01").getInt("count"));
        assertEquals(3, service.countBirthday("1999-12-31").getInt("count"));
    }

    @Test
    public void generatedFieldsStayImmutableAcrossAllReplacements() {
        PersonService service = new PersonService();
        JsonObject created = service.create(input(low));
        assertTrue(created.getJsonNumber("id").bigIntegerValue().signum() > 0);
        assertNotNull(java.time.OffsetDateTime.parse(created.getString("creationDate")));
        JsonObject updated = service.update(BigInteger.ONE, input(high));
        assertEquals(created.get("id"), updated.get("id"));
        assertEquals(created.get("creationDate"), updated.get("creationDate"));
        for (String field : object(high).keySet()) assertEquals(field, object(high).get(field), updated.get(field));
        service.delete(BigInteger.ONE);
        assertEquals(BigInteger.TWO, service.create(input(low)).getJsonNumber("id").bigIntegerValue());
    }
}
