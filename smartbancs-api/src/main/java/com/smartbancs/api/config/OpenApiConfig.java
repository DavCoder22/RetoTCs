package com.smartbancs.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI smartbancsOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("SmartBancs API")
                        .description("""
                                Plataforma financiera en tiempo real: CRUD de clientes, cuentas y transacciones,
                                transferencias atómicas, ledger de doble asiento, idempotencia y reglas de negocio.

                                Reglas de negocio:
                                - El saldo inicial de una cuenta debe ser 0; la justificación nace con un DEPOSIT (422 en caso contrario).
                                - Toda cuenta debe cumplir el invariante contable: saldo == suma de sus asientos en el ledger.
                                - Cada transacción es idempotente mediante idempotencyKey: reintentar no duplica.
                                - Clientes con cuentas, cuentas con saldo o con historial no se pueden borrar (409).

                                Los cuerpos de los ejemplos vienen precargados para facilitar la demostración.""")
                        .version("0.1.0")
                        .contact(new Contact().name("David Malquin"))
                        .license(new License().name("GPL-2.0").identifier("GPL-2.0")))
                .tags(List.of(
                        new Tag().name("Customers").description("Identidad y gestión de clientes (CRUD)."),
                        new Tag().name("Accounts").description("Cuentas bancarias, moneda, límites y estados."),
                        new Tag().name("Transactions").description("Depósitos, retiros, transferencias, ledger e idempotencia."),
                        new Tag().name("Health").description("Probe de disponibilidad del servicio.")));
    }
}