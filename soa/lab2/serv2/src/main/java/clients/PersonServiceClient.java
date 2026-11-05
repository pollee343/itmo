package clients;

import exception.ApiException;
import http.PersonListResponse;
import model.Color;
import model.Country;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import org.springframework.web.client.RestClientException;

@Component
public class PersonServiceClient {

    private static final int PAGE_SIZE = 100;

    private final RestTemplate restTemplate;
    private final String personServiceBaseUrl;

    public PersonServiceClient(
            RestTemplate restTemplate,
            @Value("${person.service.base-url}") String personServiceBaseUrl
    ) {
        this.restTemplate = restTemplate;
        this.personServiceBaseUrl = removeTrailingSlash(personServiceBaseUrl);
    }

    public long countByHairColor(Color hairColor) {
        int offset = 0;
        long total = 0;

        while (true) {
            String url = UriComponentsBuilder
                    .fromHttpUrl(personServiceBaseUrl + "/persons")
                    .queryParam("hairColor", hairColor.name())
                    .queryParam("limit", PAGE_SIZE)
                    .queryParam("offset", offset)
                    .build()
                    .encode()
                    .toUriString();

            PersonListResponse response = executeGet(url);
            total += response.getItems().size();

            Integer nextOffset = response.getNextOffset();

            if (nextOffset == null) {
                return total;
            }

            validateNextOffset(offset, nextOffset);
            offset = nextOffset;
        }
    }

    public long countByNationalityAndEyeColor(Country nationality, Color eyeColor) {
        int offset = 0;
        long total = 0;

        while (true) {
            String url = UriComponentsBuilder
                    .fromHttpUrl(personServiceBaseUrl + "/persons")
                    .queryParam("nationality", nationality.name())
                    .queryParam("eyeColor", eyeColor.name())
                    .queryParam("limit", PAGE_SIZE)
                    .queryParam("offset", offset)
                    .build()
                    .encode()
                    .toUriString();

            PersonListResponse response = executeGet(url);
            total += response.getItems().size();

            Integer nextOffset = response.getNextOffset();

            if (nextOffset == null) {
                return total;
            }

            validateNextOffset(offset, nextOffset);
            offset = nextOffset;
        }
    }

    private PersonListResponse executeGet(String url) {
        try {
            PersonListResponse response = restTemplate.getForObject(url, PersonListResponse.class);

            if (response == null || response.getItems() == null) {
                throw new ApiException(
                        HttpStatus.BAD_GATEWAY,
                        "UPSTREAM_INVALID_RESPONSE",
                        "Не удалось получить корректный ответ от сервиса Person."
                );
            }

            return response;
        } catch (HttpClientErrorException.NotFound e) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "RESOURCE_NOT_FOUND",
                    "Ресурс по запрошенному URL не найден на сервере"
            );
        } catch (HttpServerErrorException.ServiceUnavailable e) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SERVICE_UNAVAILABLE",
                    "Сервис временно недоступен. Повторите запрос позже.",
                    e
            );
        } catch (ResourceAccessException e) {
            throw new ApiException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "UPSTREAM_TIMEOUT",
                    "Сервис Person временно недоступен",
                    e
            );
        } catch (RestClientException e) {
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "UPSTREAM_INVALID_RESPONSE",
                    "Не удалось получить корректный ответ от сервиса Person."
            );
        }
    }

    private void validateNextOffset(int currentOffset, int nextOffset) {
        if (nextOffset <= currentOffset) {
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "UPSTREAM_INVALID_RESPONSE",
                    "Не удалось получить корректный ответ от сервиса Person."
            );
        }
    }

    private static String removeTrailingSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
