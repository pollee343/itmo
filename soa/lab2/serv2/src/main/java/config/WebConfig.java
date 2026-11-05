package config;

import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.HttpURLConnection;
import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.GeneralSecurityException;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

@Configuration
@EnableWebMvc
@PropertySource("classpath:application.properties")
@ComponentScan(basePackages = {"controllers", "services", "clients", "exception"})
public class WebConfig implements WebMvcConfigurer {

    @Value("${frontend.origin}")
    private String frontendOrigin;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/demography/**")
                .allowedOrigins(frontendOrigin)
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("Accept", "Content-Type")
                .maxAge(3600);
    }

    @Bean
    public static PropertySourcesPlaceholderConfigurer properties() {
        return new PropertySourcesPlaceholderConfigurer();
    }

    @Bean
    public RestTemplate restTemplate(
            @Value("${person.service.trust-store:}") String trustStorePath,
            @Value("${person.service.trust-store-password:}") String trustStorePassword
    ) throws GeneralSecurityException, IOException {
        final SSLContext sslContext;
        if (trustStorePath.isBlank()) {
            sslContext = SSLContext.getDefault();
        } else {
            KeyStore trustStore = KeyStore.getInstance("PKCS12");
            try (InputStream stream = Files.newInputStream(Path.of(trustStorePath))) {
                trustStore.load(stream, trustStorePassword.toCharArray());
            }

            TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init(trustStore);
            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, managers.getTrustManagers(), null);
        }

        var requestFactory = new SimpleClientHttpRequestFactory() {
            protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                if ((connection instanceof HttpsURLConnection https)) {
                    https.setSSLSocketFactory(sslContext.getSocketFactory());
                    https.setInstanceFollowRedirects(false);
                }
            }
        };
        requestFactory.setConnectTimeout(5_000);
        requestFactory.setReadTimeout(10_000);
        return new RestTemplate(requestFactory);
    }
}
