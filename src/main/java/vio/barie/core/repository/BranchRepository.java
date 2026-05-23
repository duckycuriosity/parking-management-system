package vio.barie.core.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.entity.BranchEntity;

import java.util.List;
import java.util.Optional;

public interface BranchRepository extends JpaRepository<BranchEntity, Long>, JpaSpecificationExecutor<BranchEntity> {


    List<BranchEntity> findByOrganizeId(@Param("organizeId") Long organizeId);
    
    Page<BranchEntity> findByOrganizeId(@Param("organizeId") Long organizeId, Pageable pageable);

    boolean existsByOrganizeIdAndBranchCodeIgnoreCase(Long organizeId, String branchCode);

    boolean existsByOrganizeIdAndBranchCodeAndIdNot(Long organizeId, String branchCode, Long id);


    Optional<BranchEntity> findByIdAndOrganize_Id(Long id, Long organizeId);

    boolean existsByOrganizeIdAndBranchCodeIgnoreCaseAndIdNot(Long organizeId, String newCode, Long id);

    @Query("SELECT b.branchCode FROM BranchEntity b WHERE b.id = :id")
    String findBranchCodeById(@Param("id") Integer id);

    @Query("SELECT b.address FROM BranchEntity b WHERE b.id = :id")
    String findAdressById(@Param("id") Integer id);
}