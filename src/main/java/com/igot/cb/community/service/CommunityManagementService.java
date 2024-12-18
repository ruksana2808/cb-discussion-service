package com.igot.cb.community.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.igot.cb.pores.util.ApiResponse;
import java.util.Map;

/**
 * @author mahesh.vakkund
 */
public interface CommunityManagementService {

    ApiResponse create(JsonNode demandsJson, String authToken);

    ApiResponse read(String communityId, String authToken);

    ApiResponse delete(String communityId, String authToken);

    ApiResponse update(JsonNode communityDetails, String authToken);

    ApiResponse joinCommunity(Map<String, Object> request, String authToken);

    ApiResponse communitiesJoinedByUser(String authToken);

    ApiResponse listOfUsersJoined(String communityId, String authToken);

    ApiResponse unJoinCommunity(Map<String, Object> request, String authToken);
}
