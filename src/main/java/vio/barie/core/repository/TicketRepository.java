package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vio.barie.core.entity.SysUserEntity;
import vio.barie.core.entity.TicketEntity;

public interface TicketRepository extends JpaRepository<TicketEntity, Long> {
}