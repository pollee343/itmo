package ru.itmo.serv1.resource;

import jakarta.inject.Inject;
import jakarta.enterprise.context.RequestScoped;
import jakarta.json.JsonObject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import ru.itmo.serv1.service.PersonService;
import ru.itmo.serv1.service.PersonValidation;

import java.math.BigInteger;

@RequestScoped
@Path("/persons")
@Produces(MediaType.APPLICATION_JSON)
public class PersonResource {
    private static final String PERSON_PATH = "/{id:(?!(?:max-weight|count-by-birthday|search-by-name)(?:/|$))[^/]+}";

    @Inject
    private PersonService service;

    @GET
    public JsonObject list(@Context UriInfo uri) {
        return service.list(uri.getQueryParameters(), null);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(String body, @Context UriInfo uri) {
        JsonObject person = service.create(PersonValidation.input(body));
        return Response.created(uri.getAbsolutePathBuilder()
                        .path(person.getJsonNumber("id")
                                .toString())
                        .build())
                .entity(person)
                .build();
    }

    @GET
    @Path(PERSON_PATH)
    public JsonObject get(@PathParam("id") String id) {
        return service.get(id(id));
    }

    @PUT
    @Path(PERSON_PATH)
    @Consumes(MediaType.APPLICATION_JSON)
    public JsonObject update(@PathParam("id") String id, String body) {
        BigInteger number = id(id);
        return service.update(number, PersonValidation.input(body));
    }

    @DELETE
    @Path(PERSON_PATH)
    public Response delete(@PathParam("id") String id) {
        service.delete(id(id));
        return Response.noContent()
                .build();
    }

    @GET
    @Path("/max-weight")
    public JsonObject maxWeight() {
        return service.maxWeight();
    }

    @GET
    @Path("/count-by-birthday")
    public JsonObject countBirthday(@QueryParam("birthday") String birthday) {
        return service.countBirthday(birthday);
    }

    @GET
    @Path("/search-by-name")
    public JsonObject search(@QueryParam("substring") String substring, @Context UriInfo uri) {
        if (substring == null || substring.isEmpty())
            throw PersonValidation.bad("substring is required and must not be empty");
        return service.list(uri.getQueryParameters(), substring);
    }

    private BigInteger id(String text) {
        try {
            BigInteger id = new BigInteger(text);
            if (id.signum() <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException e) {
            throw PersonValidation.bad("id must be a positive integer");
        }
    }
}
