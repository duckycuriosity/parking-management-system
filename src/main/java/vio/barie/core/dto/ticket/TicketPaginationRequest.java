package vio.barie.core.dto.ticket;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import vio.barie.core.dto.PaginationRequest;

@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TicketPaginationRequest extends PaginationRequest {
    private String plateNumber;
    private String status;
//    private String startDate;
//    private String endDate;
}

