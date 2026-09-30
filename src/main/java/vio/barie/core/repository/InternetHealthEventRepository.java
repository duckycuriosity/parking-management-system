package vio.barie.core.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import vio.barie.core.entity.InternetHealthEventEntity;

@Repository
public interface InternetHealthEventRepository extends JpaRepository<InternetHealthEventEntity, Long> {
    Page<InternetHealthEventEntity> findAll(Pageable pageable);
}
