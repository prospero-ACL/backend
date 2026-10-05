package com.prospero_acl.backend.repo;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.prospero_acl.backend.model.IngestionJob;

@Repository
public interface IngestionJobRepo extends JpaRepository<IngestionJob, UUID> {
  Optional<IngestionJob> findByIdAndOwner_Id(UUID id, UUID ownerId);
}
