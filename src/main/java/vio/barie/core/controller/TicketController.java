package vio.barie.core.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import vio.barie.core.dto.Result;
import vio.barie.core.dto.TicketCreateRequest;
import vio.barie.core.dto.ticket.TicketDTO;
import vio.barie.core.dto.ticket.TicketPaginationRequest;
import vio.barie.core.dto.ticket.TicketRegisterRequest;
import vio.barie.core.entity.TicketEntity;
import org.springframework.data.domain.*;
import vio.barie.core.service.TicketService;

import java.util.List;

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

    //    @PreAuthorize("hasAnyAuthority(T(vio.barie.core.common.RoleEnum).ONLY_ORG_EDIT())")
    @PostMapping("/create")
    public Result<TicketDTO> create(@RequestBody TicketCreateRequest req) {
        TicketDTO data = ticketService.createTicket(req);
        return Result.ok("Tạo vé thành công!", data);
    }

    //    @PreAuthorize("hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_VIEW())")
    @GetMapping("/all")
    public Result<List<TicketDTO>> getAll() {
        List<TicketDTO> data = ticketService.getAllTickets();
        return Result.ok(data);
    }

    //    @PreAuthorize("hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_VIEW())")
    @GetMapping("/{id}")
    public Result<TicketDTO> getById(@PathVariable Long id) {
        return Result.ok(ticketService.getById(id));
    }

    //    @PreAuthorize("hasAnyAuthority(T(vio.barie.core.common.RoleEnum).ONLY_ORG_EDIT())")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        ticketService.deleteTicketById(id);
        return Result.ok("Xóa vé thành công!", null);
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
