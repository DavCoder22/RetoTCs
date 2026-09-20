package com.smartbancs.api.controller;

import com.smartbancs.api.dto.CreateCustomerRequest;
import com.smartbancs.api.dto.CustomerResponse;
import com.smartbancs.api.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Customers", description = "Identidad y gestión de clientes (CRUD).")
@RestController
@RequestMapping("/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @Operation(summary = "Listar clientes", description = "Devuelve todos los clientes registrados con su segmento.")
    @ApiResponse(responseCode = "200", description = "Lista de clientes.")
    @GetMapping
    public List<CustomerResponse> list() {
        return customerService.findAll();
    }

    @Operation(summary = "Obtener cliente por id", description = "Recupera un cliente por su identificador.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cliente encontrado."),
            @ApiResponse(responseCode = "404", description = "Cliente no encontrado.")
    })
    @GetMapping("/{id}")
    public CustomerResponse get(@PathVariable UUID id) {
        return customerService.findById(id);
    }

    @Operation(summary = "Crear cliente", description = "Registra un cliente. El correo debe ser único.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Cliente creado."),
            @ApiResponse(responseCode = "400", description = "Validación fallida (nombre, correo o segmento)."),
            @ApiResponse(responseCode = "409", description = "El correo ya está registrado.")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerResponse create(@Valid @RequestBody CreateCustomerRequest body) {
        return customerService.create(body.fullName(), body.email(), body.segment());
    }

    @Operation(summary = "Actualizar cliente", description = "Modifica los datos de un cliente existente.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cliente actualizado."),
            @ApiResponse(responseCode = "400", description = "Validación fallida (nombre, correo o segmento)."),
            @ApiResponse(responseCode = "404", description = "Cliente no encontrado."),
            @ApiResponse(responseCode = "409", description = "El correo ya está registrado por otro cliente.")
    })
    @PutMapping("/{id}")
    public CustomerResponse update(@PathVariable UUID id, @Valid @RequestBody CreateCustomerRequest body) {
        return customerService.update(id, body.fullName(), body.email(), body.segment());
    }

    @Operation(summary = "Eliminar cliente", description = "Borra un cliente solo si no tiene cuentas asociadas.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Cliente eliminado."),
            @ApiResponse(responseCode = "404", description = "Cliente no encontrado."),
            @ApiResponse(responseCode = "409", description = "Tiene cuentas asociadas y no puede borrarse.")
    })
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        customerService.delete(id);
    }
}