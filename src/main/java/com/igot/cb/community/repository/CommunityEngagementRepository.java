package com.igot.cb.community.repository;

import com.igot.cb.community.entity.CommunityEngagementEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * @author mahesh.vakkund
 */
public interface CommunityEngagementRepository extends JpaRepository<CommunityEngagementEntity, String> {

    Optional<CommunityEngagementEntity> findByCommunityIdAndIsActive(String communityId, boolean isActive);
}
