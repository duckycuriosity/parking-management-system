package vio.barie.core.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import vio.barie.core.dto.Result;
import vio.barie.core.dto.ticket.TicketDTO;
import vio.barie.core.dto.ticket.TicketPaginationRequest;
import vio.barie.core.dto.ticket.TicketRegisterRequest;
import vio.barie.core.entity.TicketEntity;
import org.springframework.data.domain.*;
import vio.barie.core.service.TicketService;

// controller cho mọi hoạt động đk vé
@RestController
@RequestMapping("/api/ticket")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    @PreAuthorize("hasAnyAuthority(T(vio.barie.core.common.RoleEnum).ONLY_ORG_EDIT())")
    @PostMapping("/register")
    public Result<TicketEntity> register(@RequestBody TicketRegisterRequest req) {
        TicketEntity data = ticketService.registerTicket(req);
        return Result.ok("Thành công!", data);
    }

    @PreAuthorize("hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_VIEW())")
    @GetMapping("/{id}")
    public Result<TicketEntity> getById(@PathVariable Long id) {
        return Result.ok(ticketService.getById(id));
    }

    @PreAuthorize("hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_VIEW())")
    @PostMapping("/list")
    public Result<Page<TicketDTO>> list(@Valid @RequestBody(required = false) TicketPaginationRequest paginationRequest) {
        if (paginationRequest == null) {
            paginationRequest = TicketPaginationRequest.builder().build();
        }
        Page<TicketDTO> data = ticketService.list(paginationRequest);
        return Result.ok(data);
    }
}
