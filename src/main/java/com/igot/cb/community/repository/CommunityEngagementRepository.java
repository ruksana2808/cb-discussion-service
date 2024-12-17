package com.igot.cb.community.repository;

import com.igot.cb.community.entity.CommunityEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * @author mahesh.vakkund
 */
public interface CommunityEngagementRepository extends JpaRepository<CommunityEntity, String> {

    Optional<CommunityEntity> findByCommunityIdAndIsActive(String communityId, boolean isActive);
}
