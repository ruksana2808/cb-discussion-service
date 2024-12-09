package com.igot.cb.community.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.igot.cb.community.service.CommunityEngagementService;
import com.igot.cb.pores.dto.CustomResponse;
import com.igot.cb.pores.util.ApiResponse;
import com.igot.cb.pores.util.Constants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * @author mahesh.vakkund
 */
@RestController
@RequestMapping("/community")
public class CommunityController {
    @Autowired
    private CommunityEngagementService communityEngagementService;

    @PostMapping("/v1/create")
    public ResponseEntity<ApiResponse> create(@RequestBody JsonNode communityDetails,
                                              @RequestHeader(Constants.X_AUTH_TOKEN) String authToken) {
        ApiResponse response = communityEngagementService.create(communityDetails, authToken);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @GetMapping("/read/{communityId}")
    public ResponseEntity<ApiResponse> read(@PathVariable String communityId,
                                            @RequestHeader(Constants.X_AUTH_TOKEN) String authToken) {
        ApiResponse response = communityEngagementService.read(communityId, authToken);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @DeleteMapping("/delete/{communityId}")
    public ResponseEntity<ApiResponse> delete(@PathVariable String communityId,
                                              @RequestHeader(Constants.X_AUTH_TOKEN) String authToken) {
        ApiResponse response = communityEngagementService.delete(communityId, authToken);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @PutMapping("/v1/update")
    public ResponseEntity<ApiResponse> update(@RequestBody JsonNode communityDetails,
                                              @RequestHeader(Constants.X_AUTH_TOKEN) String authToken) {
        ApiResponse response = communityEngagementService.update(communityDetails, authToken);
        return new ResponseEntity<>(response, response.getResponseCode());
    }
}
