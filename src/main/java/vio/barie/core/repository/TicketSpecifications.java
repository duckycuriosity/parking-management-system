package vio.barie.core.repository;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.jpa.domain.Specification;
import vio.barie.core.dto.ticket.TicketPaginationRequest;
import vio.barie.core.entity.TicketEntity;
import vio.barie.core.entity.VehicleEntity;

import java.util.ArrayList;
import java.util.List;

public class TicketSpecifications {

    public static Specification<TicketEntity> buildPaginationSpecification(String plateNumber,String status) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Filter theo vehicle (plate number)
            if (StringUtils.isNotBlank(plateNumber)){
                Join<TicketEntity, VehicleEntity> vehicleJoin = root.join("vehicle", JoinType.INNER);
                predicates.add(cb.like(vehicleJoin.get("plateNumber"),
                        "%" + plateNumber.trim().toUpperCase() + "%"));
            }

            // Filter theo status
            if (StringUtils.isNotBlank(status)){
                predicates.add(cb.equal(root.get("status"), status.trim().toUpperCase()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
