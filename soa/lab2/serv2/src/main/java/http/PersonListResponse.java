package http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dto.Person;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class PersonListResponse {
    private List<Person> items;
    private Integer nextOffset;
}
