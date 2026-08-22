package com.prospero_acl.backend.repo;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.prospero_acl.backend.model.Report;
import com.prospero_acl.backend.model.enums.ReportStatus;

@Repository
public interface ReportRepo extends JpaRepository<Report, UUID> {
  Optional<Report> findByIdAndOwner_Id(UUID id, UUID ownerId);

  // TODO: Consider renaming this method
  Optional<Report> findFirstByOwner_IdAndStatusInOrderByCreatedAtDesc(
      UUID ownerId, Collection<ReportStatus> statuses);
}
