package ru.itmo.serv1.model;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

@AllArgsConstructor
public class Person {
    @Getter
    private final BigInteger id;
    @Getter
    private final String creationDate;
    private final JsonObject fields;

    public Person(BigInteger id, JsonObject fields) {
        this(id, OffsetDateTime.now()
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), fields);
    }

    public JsonObject toJson() {
        return Json.createObjectBuilder(fields)
                .add("id", id)
                .add("creationDate", creationDate)
                .build();
    }
}
