package org.fmr.findmyreads.configs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /**
     * Single shared RestClient bean.
     * No base URL set here — each client (OpenLibraryClient) sets its own URI per call.
     */
    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        return builder
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, "FindMyReads/1.0 amanchandra.in@gmail.com")
                .build();
    }
}
