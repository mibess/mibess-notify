package com.mibess.notify.shared;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ProblemAdvice {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> invalid(IllegalArgumentException e) { return response(400, e.getMessage()); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalidBody(MethodArgumentNotValidException e) { return response(400, "Dados inválidos: verifique os campos obrigatórios e formatos."); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> conflict() { return response(409, "Código já utilizado ou referência inválida."); }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> status(ResponseStatusException e) { return response(e.getStatusCode().value(), e.getReason()); }
    private ResponseEntity<ProblemDetail> response(int status, String detail) { return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail == null ? "Solicitação não permitida" : detail)); }
}
