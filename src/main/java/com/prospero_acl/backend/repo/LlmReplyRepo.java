package com.prospero_acl.backend.repo;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.prospero_acl.backend.model.LlmReply;

@Repository
public interface LlmReplyRepo extends JpaRepository<LlmReply, UUID> {

}
