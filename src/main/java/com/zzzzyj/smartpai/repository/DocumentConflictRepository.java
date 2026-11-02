package com.zzzzyj.smartpai.repository;

import com.zzzzyj.smartpai.model.DocumentConflict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface DocumentConflictRepository extends JpaRepository<DocumentConflict, Long> {

    /** 管理员处理时锁定记录，避免多实例重复执行同一处理动作。 */
    @Override
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    java.util.Optional<DocumentConflict> findById(Long id);

    boolean existsByPairKey(String pairKey);

    Page<DocumentConflict> findByStatus(DocumentConflict.Status status, Pageable pageable);

    boolean existsByPairKeyAndStatusIn(String pairKey, Collection<DocumentConflict.Status> statuses);

    @Query("select c from DocumentConflict c where c.status = :status "
            + "and (c.newFileMd5 = :fileMd5 or c.existingFileMd5 = :fileMd5)")
    List<DocumentConflict> findByStatusAndFileMd5(@Param("status") DocumentConflict.Status status,
                                                  @Param("fileMd5") String fileMd5);
}
