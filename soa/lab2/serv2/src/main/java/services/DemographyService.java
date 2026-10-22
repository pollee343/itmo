package services;

import clients.PersonServiceClient;
import http.CountResponse;
import lombok.AllArgsConstructor;
import model.Color;
import model.Country;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class DemographyService {

    private final PersonServiceClient personServiceClient;

    public CountResponse countPersonsByHairColor(Color hairColor) {
        long count = personServiceClient.countByHairColor(hairColor);
        return new CountResponse(count);
    }

    public CountResponse countPersonsByNationalityAndEyeColor(Country nationality, Color eyeColor) {
        long count = personServiceClient.countByNationalityAndEyeColor(nationality, eyeColor);
        return new CountResponse(count);
    }
}
