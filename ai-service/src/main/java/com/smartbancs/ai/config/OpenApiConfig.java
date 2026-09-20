package com.smartbancs.ai.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI aiServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("SmartBancs AI Service")
                        .description("""
                                Servicio de recomendaciones financieras consumido de forma asíncrona por la API.

                                Estado actual: stub (501 Not Implemented) — la integración con el proveedor de IA
                                está pendiente y debe declararse el uso de IA en la entrega final.""")
                        .version("0.1.0"))
                .tags(List.of(
                        new Tag().name("Recommendations").description("Generación de recomendaciones personalizadas (asíncrono).")));
    }
}