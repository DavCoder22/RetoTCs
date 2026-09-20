package com.smartbancs.api.controller;

import com.smartbancs.api.dto.CreateTransactionRequest;
import com.smartbancs.api.dto.LedgerEntryResponse;
import com.smartbancs.api.dto.TransactionResponse;
import com.smartbancs.api.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Transactions", description = "Depósitos, retiros, transferencias, ledger e idempotencia.")
@RestController
@RequestMapping("/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @Operation(summary = "Listar transacciones", description = "Devuelve todas las transacciones operadas.")
    @ApiResponse(responseCode = "200", description = "Lista de transacciones.")
    @GetMapping
    public List<TransactionResponse> list() {
        return transactionService.findAll();
    }

    @Operation(summary = "Obtener transacción por id", description = "Recupera una transacción por su identificador.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transacción encontrada."),
            @ApiResponse(responseCode = "404", description = "Transacción no encontrada.")
    })
    @GetMapping("/{id}")
    public TransactionResponse get(@PathVariable UUID id) {
        return transactionService.findById(id);
    }

    @Operation(summary = "Transacciones de una cuenta", description = "Historial reciente (hasta 50) de una cuenta.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Movimientos de la cuenta."),
            @ApiResponse(responseCode = "404", description = "Cuenta no encontrada.")
    })
    @GetMapping("/account/{accountId}")
    public List<TransactionResponse> listByAccount(@PathVariable UUID accountId) {
        return transactionService.findByAccount(accountId);
    }

    @Operation(summary = "Ledger de una transacción", description = "Asientos contables de doble partida (DEBIT/CREDIT) de una transacción.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Asientos del ledger. El saldo de la cuenta es su suma."),
            @ApiResponse(responseCode = "404", description = "Transacción no encontrada.")
    })
    @GetMapping("/{id}/ledger")
    public List<LedgerEntryResponse> ledger(@PathVariable UUID id) {
        return transactionService.findLedger(id);
    }

    @Operation(summary = "Registrar transacción",
            description = "DEPOSIT abona creditAccountId; WITHDRAWAL/PAYMENT cargan debitAccountId; TRANSFER mueve fondos "
                    + "entre cuentas con asientos dobles. Usa idempotencyKey para que reintentar no duplique.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Transacción registrada y asentada en el ledger."),
            @ApiResponse(responseCode = "200", description = "Idempotente: la idempotencyKey ya fue usada; devuelve la transacción existente."),
            @ApiResponse(responseCode = "400", description = "Falta debitAccountId/creditAccountId para el tipo, o tipo no soportado."),
            @ApiResponse(responseCode = "404", description = "Cuenta no encontrada."),
            @ApiResponse(responseCode = "422", description = "Fondos insuficientes o cuenta no activa.")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse create(@Valid @RequestBody CreateTransactionRequest body) {
        return transactionService.create(
                body.type(), body.amount(), body.currency(),
                body.debitAccountId(), body.creditAccountId(),
                body.idempotencyKey(), body.reference());
    }
}