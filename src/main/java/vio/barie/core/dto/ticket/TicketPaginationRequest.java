package vio.barie.core.dto.ticket;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import vio.barie.core.common.TicketStatus;
import vio.barie.core.dto.PaginationRequest;

@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TicketPaginationRequest extends PaginationRequest {
    private String plateNumber;
    private String sortBy = "createdAt";
    private String sortDirection = "DESC";
    private String searchKey; // filter plate_number, username, phone_number
    private TicketStatus status;
    private int page = 0;
    private int size = 10;
//    private String startDate;
//    private String endDate;
}

