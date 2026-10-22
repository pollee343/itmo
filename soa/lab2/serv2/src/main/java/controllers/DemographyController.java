package controllers;

import exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import services.DemographyService;
import http.CountResponse;
import model.Color;
import model.Country;

import java.util.Locale;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequestMapping("/demography")
@RequiredArgsConstructor
public class DemographyController {

    private final DemographyService demographyService;

    @GetMapping("/hair-color/{hair-color}")
    public ResponseEntity<CountResponse> countPersonsByHairColor(
            @PathVariable("hair-color") String hairColorValue
    ) {
        Color hairColor = parseColor(hairColorValue, "hair-color");

        return ResponseEntity.ok(
                demographyService.countPersonsByHairColor(hairColor)
        );
    }

    @GetMapping("/nationality/{nationality}/eye-color/{eye-color}")
    public ResponseEntity<CountResponse> countPersonsByNationalityAndEyeColor(
            @PathVariable("nationality") String nationalityValue,
            @PathVariable("eye-color") String eyeColorValue
    ) {
        Country nationality = parseCountry(nationalityValue);
        Color eyeColor = parseColor(eyeColorValue, "eye-color");

        return ResponseEntity.ok(
                demographyService.countPersonsByNationalityAndEyeColor(nationality, eyeColor)
        );
    }

    private Color parseColor(String value, String parameterName) {
        try {
            return Color.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(
                    BAD_REQUEST,
                    "INVALID_REQUEST",
                    "invalid " + parameterName + " value"
            );
        }
    }

    private Country parseCountry(String value) {
        try {
            return Country.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(
                    BAD_REQUEST,
                    "INVALID_REQUEST",
                    "invalid nationality value"
            );
        }
    }
}
