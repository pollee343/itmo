package ru.itmo.serv1.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.*;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MultivaluedMap;
import ru.itmo.serv1.model.Person;

import java.math.BigInteger;
import java.util.*;

@ApplicationScoped
public class PersonService {
    private final Map<BigInteger, Person> persons = new LinkedHashMap<>();
    private BigInteger nextId = BigInteger.ONE;

    public synchronized JsonObject create(JsonObject fields) {
        Person person = new Person(nextId, fields);
        persons.put(nextId, person);
        nextId = nextId.add(BigInteger.ONE);
        return person.toJson();
    }

    public synchronized JsonObject get(BigInteger id) {
        return find(id).toJson();
    }

    public synchronized JsonObject update(BigInteger id, JsonObject fields) {
        Person old = find(id);
        Person person = new Person(id, old.getCreationDate(), fields);
        persons.put(id, person);
        return person.toJson();
    }

    public synchronized void delete(BigInteger id) {
        find(id);
        persons.remove(id);
    }

    private Person find(BigInteger id) {
        Person person = persons.get(id);
        if (person == null) throw new NotFoundException("OBJECT_NOT_FOUND:" + id);
        return person;
    }

    public synchronized JsonObject maxWeight() {
        JsonObject best = null;
        for (Person person : persons.values()) {
            JsonObject item = person.toJson();
            if (!item.isNull("weight") && (best == null || item.getJsonNumber("weight")
                    .bigDecimalValue()
                    .compareTo(best.getJsonNumber("weight")
                            .bigDecimalValue()) > 0)) best = item;
        }
        if (best == null) throw new NotFoundException("MAX_WEIGHT_OBJECT_NOT_FOUND");
        return best;
    }

    public synchronized JsonObject countBirthday(String birthday) {
        Object boundary = PersonValidation.filter("birthday", birthday);
        if (boundary == null) throw PersonValidation.bad("birthday must be a date");
        long count = 0;
        for (Person person : persons.values()) {
            Object date = value(person.toJson(), "birthday");
            if (date != null && compare(date, boundary) > 0) count++;
        }
        return Json.createObjectBuilder()
                .add("count", count)
                .build();
    }

    public synchronized JsonObject list(MultivaluedMap<String, String> query, String substring) {
        int limit = pageNumber(query.getFirst("limit"), 20, 1, 100);
        BigInteger offset = BigInteger.ZERO;
        try {
            if (query.containsKey("offset")) offset = new BigInteger(query.getFirst("offset"));
            if (offset.signum() < 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            throw PersonValidation.bad("offset must be a nonnegative integer");
        }
        Map<String, Object> filters = new LinkedHashMap<>();
        List<String> sorts = new ArrayList<>();
        if (substring == null) {
            for (String field : PersonValidation.FIELDS) {
                if (query.containsKey(field)) filters.put(field, PersonValidation.filter(field, query.getFirst(field)));
            }
            if (query.containsKey("sort")) sorts.addAll(query.get("sort"));
            for (String sort : sorts) {
                String[] parts = sort.split(",", -1);
                if (parts.length != 2 || !PersonValidation.FIELDS.contains(parts[0]) || !(parts[1].equals("asc") || parts[1].equals("desc")))
                    throw PersonValidation.bad("Invalid sort: " + sort);
            }
        }
        List<JsonObject> items = new ArrayList<>();
        for (Person person : persons.values()) {
            JsonObject item = person.toJson();
            boolean matches = substring == null || item.getString("name")
                    .contains(substring);
            for (Map.Entry<String, Object> filter : filters.entrySet()) {
                if (compare(value(item, filter.getKey()), filter.getValue()) != 0) matches = false;
            }
            if (matches) items.add(item);
        }
        items.sort((left, right) -> {
            for (String sort : sorts) {
                String[] parts = sort.split(",");
                int order = compare(value(left, parts[0]), value(right, parts[0]));
                if (order != 0) return parts[1].equals("asc") ? order : -order;
            }
            return 0;
        });
        int start = offset.min(BigInteger.valueOf(items.size()))
                .intValue();
        int end = (int) Math.min((long) start + limit, items.size());
        JsonArrayBuilder array = Json.createArrayBuilder();
        for (int i = start; i < end; i++) array.add(items.get(i));
        JsonObjectBuilder result = Json.createObjectBuilder()
                .add("items", array);
        if (end < items.size()) result.add("nextoffset", end);
        else result.addNull("nextoffset");
        return result.build();
    }

    private int pageNumber(String value, int fallback, int min, int max) {
        if (value == null) return fallback;
        try {
            int number = Integer.parseInt(value);
            if (number < min || number > max) throw new NumberFormatException();
            return number;
        } catch (NumberFormatException e) {
            throw PersonValidation.bad("limit must be an integer from 1 to 100");
        }
    }

    private Object value(JsonObject person, String field) {
        JsonValue value;
        if (field.startsWith("coordinates") || field.startsWith("location")) {
            String parent = field.startsWith("coordinates") ? "coordinates" : "location";
            if (person.isNull(parent)) return null;
            String nested = field.substring(parent.length())
                    .toLowerCase(Locale.ROOT);
            value = person.getJsonObject(parent)
                    .get(nested);
        } else value = person.get(field);
        if (value == null || value == JsonValue.NULL) return null;
        if (value.getValueType() == JsonValue.ValueType.NUMBER)
            return ((JsonNumber) value).bigDecimalValue();
        return PersonValidation.filter(field, ((JsonString) value).getString());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private int compare(Object left, Object right) {
        if (left == null) return right == null ? 0 : -1;
        if (right == null) return 1;
        return ((Comparable) left).compareTo(right);
    }
}
