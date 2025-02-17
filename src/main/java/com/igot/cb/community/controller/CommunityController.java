package com.igot.cb.community.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.igot.cb.community.service.CommunityManagementService;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.util.ApiResponse;
import com.igot.cb.pores.util.Constants;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * @author mahesh.vakkund
 */
@RestController
@RequestMapping("/community/v1")
public class CommunityController {
    @Autowired
    private CommunityManagementService communityManagementService;

    @PostMapping("/create")
    public ResponseEntity<ApiResponse> create(@RequestBody JsonNode communityDetails,
                                              @RequestHeader(Constants.X_AUTH_TOKEN) String authToken) {
        ApiResponse response = communityManagementService.create(communityDetails, authToken);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @GetMapping("/read/{communityId}")
    public ResponseEntity<ApiResponse> read(@PathVariable String communityId,
                                            @RequestHeader(Constants.X_AUTH_TOKEN) String authToken) {
        ApiResponse response = communityManagementService.read(communityId, authToken);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @DeleteMapping("/delete/{communityId}")
    public ResponseEntity<ApiResponse> delete(@PathVariable String communityId,
                                              @RequestHeader(Constants.X_AUTH_TOKEN) String authToken) {
        ApiResponse response = communityManagementService.delete(communityId, authToken);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @PutMapping("/update")
    public ResponseEntity<ApiResponse> update(@RequestBody JsonNode communityDetails,
                                              @RequestHeader(Constants.X_AUTH_TOKEN) String authToken) {
        ApiResponse response = communityManagementService.update(communityDetails, authToken);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @PutMapping("/join")
    public ResponseEntity<ApiResponse> join(@RequestBody Map<String, Object> request,
        @RequestHeader(Constants.X_AUTH_TOKEN) String authToken) {
        ApiResponse response = communityManagementService.joinCommunity(request, authToken);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @GetMapping("/user/communities")
    public ResponseEntity<ApiResponse> readJoin(@RequestHeader(Constants.X_AUTH_TOKEN) String authToken) {
        ApiResponse response = communityManagementService.communitiesJoinedByUser(authToken);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @PostMapping("/community/listuser")
    public ResponseEntity<ApiResponse> listOfUsersJoined(
        @RequestHeader(Constants.X_AUTH_TOKEN) String authToken,
        @RequestBody Map<String, Object> requestPayload) {

        ApiResponse response = communityManagementService.listOfUsersJoined(authToken, requestPayload);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }


    @PutMapping("/unjoin")
    public ResponseEntity<ApiResponse> unJoin(@RequestBody Map<String, Object> request,
        @RequestHeader(Constants.X_AUTH_TOKEN) String authToken) {
        ApiResponse response = communityManagementService.unJoinCommunity(request, authToken);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @PostMapping("/search")
    public ResponseEntity<ApiResponse> search(@RequestBody SearchCriteria searchCriteria) {
        ApiResponse response = communityManagementService.searchCommunity(searchCriteria);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @PostMapping("/category/create")
    public ResponseEntity<ApiResponse> communityCtreate(@RequestBody JsonNode communityDetails,
        @RequestHeader(Constants.X_AUTH_TOKEN) String authToken) {
        ApiResponse response = communityManagementService.categoryCreate(communityDetails, authToken);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @GetMapping("/category/read/{categoryId}")
    public ResponseEntity<ApiResponse> readCategory(@PathVariable String categoryId,
        @RequestHeader(Constants.X_AUTH_TOKEN) String authToken) {
        ApiResponse response = communityManagementService.readCategory(categoryId, authToken);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @DeleteMapping("/category/delete/{categoryId}")
    public ResponseEntity<ApiResponse> deleteCategory(@PathVariable String categoryId,
        @RequestHeader(Constants.X_AUTH_TOKEN) String authToken) {
        ApiResponse response = communityManagementService.deleteCategory(categoryId, authToken);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @PutMapping("/category/update")
    public ResponseEntity<ApiResponse> updateCategory(@RequestBody JsonNode categoryDetails,
        @RequestHeader(Constants.X_AUTH_TOKEN) String authToken) {
        ApiResponse response = communityManagementService.updateCategory(categoryDetails, authToken);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @GetMapping("/category/list")
    public ResponseEntity<ApiResponse> readCategory() {
        ApiResponse response = communityManagementService.listOfCategory();
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @PostMapping("/subcategory/list")
    public ResponseEntity<ApiResponse> readSubCategory(@RequestBody SearchCriteria searchCriteria) {
        ApiResponse response = communityManagementService.listOfSubCategory(searchCriteria);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @GetMapping("/category/listAll")
    public ResponseEntity<ApiResponse> readAllCategory() {
        ApiResponse response = communityManagementService.lisAllCategoryWithSubCat();
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @PostMapping("/popular")
    public ResponseEntity<ApiResponse> getTopCommunitiesByField(@RequestBody Map<String, Object> payload) {
        ApiResponse response = communityManagementService.getPopularCommunitiesByField(payload);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @PostMapping("/report")
    public ResponseEntity<ApiResponse> report(@RequestBody Map<String, Object> reportData,
        @RequestHeader(Constants.X_AUTH_TOKEN) String token) {
        ApiResponse response = communityManagementService.report(token, reportData);
        return new ResponseEntity<>(response, response.getResponseCode());
    }
}
