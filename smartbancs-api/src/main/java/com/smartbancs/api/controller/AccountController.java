package com.smartbancs.api.controller;

import com.smartbancs.api.dto.AccountResponse;
import com.smartbancs.api.dto.CreateAccountRequest;
import com.smartbancs.api.dto.UpdateAccountRequest;
import com.smartbancs.api.service.AccountService;
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

@Tag(name = "Accounts", description = "Cuentas bancarias, moneda, límites y estados.")
@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @Operation(summary = "Listar cuentas", description = "Devuelve todas las cuentas con su saldo actual.")
    @ApiResponse(responseCode = "200", description = "Lista de cuentas.")
    @GetMapping
    public List<AccountResponse> list() {
        return accountService.findAll();
    }

    @Operation(summary = "Obtener cuenta por id", description = "Recupera una cuenta con su saldo y estado.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cuenta encontrada."),
            @ApiResponse(responseCode = "404", description = "Cuenta no encontrada.")
    })
    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id) {
        return accountService.findById(id);
    }

    @Operation(summary = "Cuentas de un cliente", description = "Lista todas las cuentas de un cliente (multicuenta y multimoneda).")
    @ApiResponse(responseCode = "200", description = "Cuentas del cliente.")
    @GetMapping("/customer/{customerId}")
    public List<AccountResponse> listByCustomer(@PathVariable UUID customerId) {
        return accountService.findByCustomer(customerId);
    }

    @Operation(summary = "Crear cuenta", description = "Abre una cuenta con saldo inicial 0. El saldo se justifica luego con un DEPOSIT.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Cuenta creada."),
            @ApiResponse(responseCode = "400", description = "El cliente no existe o validación fallida."),
            @ApiResponse(responseCode = "409", description = "El número de cuenta ya existe."),
            @ApiResponse(responseCode = "422", description = "Saldo inicial distinto de 0: debe justificarse con un DEPOSIT.")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse create(@Valid @RequestBody CreateAccountRequest body) {
        return accountService.create(body.customerId(), body.accountNumber(), body.currency(),
                body.balance(), body.dailyTransferLimit(), body.status());
    }

    @Operation(summary = "Actualizar cuenta", description = "Modifica moneda, límite diario o estado de la cuenta.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cuenta actualizada."),
            @ApiResponse(responseCode = "404", description = "Cuenta no encontrada.")
    })
    @PutMapping("/{id}")
    public AccountResponse update(@PathVariable UUID id,
                                  @RequestBody UpdateAccountRequest body) {
        return accountService.update(id, body.currency(), body.dailyTransferLimit(), body.status());
    }

    @Operation(summary = "Eliminar cuenta", description = "Borra una cuenta solo si tiene saldo 0 y sin historial de movimientos.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Cuenta eliminada."),
            @ApiResponse(responseCode = "404", description = "Cuenta no encontrada."),
            @ApiResponse(responseCode = "409", description = "Tiene saldo distinto de 0 o historial de movimientos.")
    })
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        accountService.delete(id);
    }
}