package com.url.shortly.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Documents the two credentials the API understands: a bearer access token
 * and the refresh cookie. The cookie scheme name matches the default
 * {@code AUTH_REFRESH_COOKIE_NAME}.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI shortlyOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Shortly API")
                        .version("v1")
                        .description("URL shortener platform API"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT"))
                        .addSecuritySchemes("refreshCookie", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("SHORTLY_REFRESH")));
    }
}
