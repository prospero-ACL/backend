package com.prospero_acl.backend.repo;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.prospero_acl.backend.model.Report;

@Repository
public interface ReportRepo extends JpaRepository<Report, UUID> {
  Optional<Report> findByIdAndOwner_Id(UUID id, UUID ownerId);
}
