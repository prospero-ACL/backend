package com.prospero_acl.backend.repo;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.prospero_acl.backend.model.Conversation;

@Repository
public interface ConversationRepo extends JpaRepository<Conversation, UUID> {
  Optional<Conversation> findByIdAndOwner_Id(UUID id, UUID ownerId);

  Optional<Conversation> findFirstByOwner_IdOrderByUpdatedAtDesc(UUID ownerId);
}
