package com.igot.cb.community.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.igot.cb.authentication.util.AccessTokenValidator;
import com.igot.cb.community.entity.CommunityEngagementEntity;
import com.igot.cb.community.repository.CommunityEngagementRepository;
import com.igot.cb.community.service.CommunityEngagementService;
import com.igot.cb.pores.cache.CacheService;
import com.igot.cb.pores.elasticsearch.service.EsUtilService;
import com.igot.cb.pores.exceptions.CustomException;
import com.igot.cb.pores.util.*;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.ValidationMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;


import java.io.InputStream;
import java.sql.Timestamp;
import java.util.*;

/**
 * @author mahesh.vakkund
 */
@Service
@Slf4j
public class CommunityEngagementServiceImpl implements CommunityEngagementService {

    @Autowired
    private EsUtilService esUtilService;
    @Autowired
    private CacheService cacheService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private CbServerProperties cbServerProperties;

    @Autowired
    private PayloadValidation payloadValidation;

    @Autowired
    private CommunityEngagementRepository communityEngagementRepository;

    @Autowired
    private AccessTokenValidator accessTokenValidator;

    private Logger logger = LoggerFactory.getLogger(CommunityEngagementServiceImpl.class);


    @Override
    public ApiResponse create(JsonNode communityDetails, String authToken) {
        log.info("CommunityEngagementService::create:creating community");
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_COMMUNITY_CREATE);
        validatePayload(Constants.PAYLOAD_VALIDATION_FILE, communityDetails);
        try {
            String userId = accessTokenValidator.verifyUserToken(authToken);
            if (StringUtils.isBlank(userId)) {
                response.getParams().setStatus(Constants.FAILED);
                response.getParams().setErrMsg(Constants.USER_ID_DOESNT_EXIST);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                return response;
            }
            String communityId = UUID.randomUUID().toString();
            CommunityEngagementEntity communityEngagementEntity = new CommunityEngagementEntity();
            communityEngagementEntity.setCommunityId(communityId);
            ((ObjectNode) communityDetails).put(Constants.COUNT_OF_PEOPLE_JOINED, 0);
            ((ObjectNode) communityDetails).put(Constants.CREATED_BY, userId);
            ((ObjectNode) communityDetails).put(Constants.UPDATED_BY, userId);
            communityEngagementEntity.setData(communityDetails);
            Timestamp currentTimestamp = new Timestamp(System.currentTimeMillis());
            communityEngagementEntity.setCreatedOn(currentTimestamp);
            communityEngagementEntity.setUpdatedOn(currentTimestamp);
            communityEngagementEntity.setCreated_by(userId);
            communityEngagementEntity.setActive(true);
            CommunityEngagementEntity saveJsonEntity = communityEngagementRepository.save(communityEngagementEntity);
            if (!saveJsonEntity.getData().isNull()) {
                communityDetails = addExtraproperties(saveJsonEntity.getData(), communityId, currentTimestamp);
                Map<String, Object> communityDetailsMap = objectMapper.convertValue(communityDetails, Map.class);
                esUtilService.addDocument(Constants.INDEX_NAME, Constants.INDEX_TYPE, communityId, communityDetailsMap, cbServerProperties.getElasticCommunityJsonPath());
                cacheService.putCache(Constants.REDIS_KEY_PREFIX + communityId, communityDetailsMap);
                log.info(
                        "created community");
                response.getResult().put(Constants.STATUS, Constants.SUCCESSFULLY_CREATED);
                response.getResult().put(Constants.COMMUNITY_ID, communityId);
                return response;

            } else {
                response.getParams().setStatus(Constants.FAILED);
                response.getParams().setErrMsg(Constants.FAILED);
                response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
                return response;
            }
        } catch (Exception e) {
            log.error("error occured while creating commmunity:" + e);
            throw new CustomException("error while processing", e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private JsonNode addExtraproperties(JsonNode saveJsonEntity, String id, Timestamp currentTime) {
        ObjectNode modifiedNode = (ObjectNode) saveJsonEntity; // Create a mutable copy of the JsonNode
        modifiedNode.put(Constants.COMMUNITY_ID, id);
        modifiedNode.put(Constants.CREATED_ON, String.valueOf(currentTime));
        modifiedNode.put(Constants.UPDATED_ON, String.valueOf(currentTime));
        modifiedNode.put(Constants.STATUS, Constants.ACTIVE);
        return modifiedNode;
    }

    @Override
    public ApiResponse read(String communityId, String authToken) {
        log.info("CommunityEngagementService:read:reading community");
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_ORG_BOOKMARK_READ);
        String userId = accessTokenValidator.verifyUserToken(authToken);
        if (StringUtils.isBlank(userId)) {
            logger.error("Id not found");
            response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
            response.getParams().setErrMsg(Constants.ID_NOT_FOUND);
            return response;
        }
        if (StringUtils.isEmpty(communityId)) {
            logger.error("Community Id not found");
            response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
            response.getParams().setErrMsg(Constants.ID_NOT_FOUND);
            return response;
        }
        try {
            String cachedJson = cacheService.getCache(Constants.REDIS_KEY_PREFIX + communityId);
            if (StringUtils.isNotEmpty(cachedJson)) {
                log.info("Record coming from redis cache");
                response.getParams().setErrMsg(Constants.SUCCESSFULLY_READING);
                response
                        .getResult()
                        .put(Constants.COMMUNITY_DETAILS, objectMapper.readValue(cachedJson, new TypeReference<Object>() {
                        }));
            } else {
                Optional<CommunityEngagementEntity> communityEntityOptional = communityEngagementRepository.findByCommunityIdAndIsActive(communityId, true);
                if (communityEntityOptional.isPresent()) {
                    CommunityEngagementEntity communityEngagementEntiy = communityEntityOptional.get();
                    log.info("Record coming from postgres db");
                    response.getParams().setErrMsg(Constants.SUCCESSFULLY_READING);
                    response.getResult().put(Constants.COMMUNITY_DETAILS, objectMapper.convertValue(communityEngagementEntiy.getData(), new TypeReference<Object>() {
                    }));
                } else {
                    logger.error("Invalid Id: {}", communityId);
                    response.setResponseCode(HttpStatus.NOT_FOUND);
                    response.getParams().setErrMsg(Constants.INVALID_COMMUNITY_ID);
                }
            }

        } catch (Exception e) {
            logger.error("Error while mapping JSON for id {}: {}", communityId, e.getMessage(), e);
            throw new CustomException(Constants.ERROR, "error while processing", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return response;
    }

    public void validatePayload(String fileName, JsonNode payload) {
        try {
            JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance();
            InputStream schemaStream = schemaFactory.getClass().getResourceAsStream(fileName);
            JsonSchema schema = schemaFactory.getSchema(schemaStream);
            Set<ValidationMessage> validationMessages = schema.validate(payload);
            if (!validationMessages.isEmpty()) {
                StringBuilder errorMessage = new StringBuilder("Validation error(s): \n");
                for (ValidationMessage message : validationMessages) {
                    errorMessage.append(message.getMessage()).append("\n");
                }
                throw new CustomException("Validation Error", errorMessage.toString(), HttpStatus.BAD_REQUEST);
            }
        } catch (Exception e) {
            throw new CustomException("Failed to validate payload", e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }


    public ApiResponse delete(String communityId, String authToken) {
        log.info("CommunityEngagementService:delete:deleting community");
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_COMMUNITY_DELETE);
        String userId = accessTokenValidator.verifyUserToken(authToken);
        if (StringUtils.isBlank(userId)) {
            response.getParams().setErrMsg(Constants.USER_ID_DOESNT_EXIST);
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return response;
        }
        if (StringUtils.isEmpty(communityId)) {
            logger.error("Community Id not found");
            response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
            response.getParams().setErrMsg(Constants.COMMUNITY_ID_NOT_FOUND);
            return response;
        }
        try {
            Optional<CommunityEngagementEntity> communityEntityOptional = communityEngagementRepository.findByCommunityIdAndIsActive(communityId, true);
            if (communityEntityOptional.isPresent()) {
                CommunityEngagementEntity communityEngagementEntity = communityEntityOptional.get();
                communityEngagementEntity.setActive(false);
                communityEngagementRepository.save(communityEngagementEntity);
                JsonNode esSave = communityEngagementEntity.getData();
                ((ObjectNode) esSave).put(Constants.STATUS, Constants.INACTIVE);
                Timestamp currentTimestamp = new Timestamp(System.currentTimeMillis());
                ((ObjectNode) esSave).put(Constants.UPDATED_ON, String.valueOf(currentTimestamp));
                Map<String, Object> map = objectMapper.convertValue(esSave, Map.class);
                esUtilService.updateDocument(Constants.INDEX_NAME, Constants.INDEX_TYPE, communityId, map, cbServerProperties.getElasticCommunityJsonPath());
                cacheService.deleteCache(Constants.REDIS_KEY_PREFIX + communityId);
                response.getResult().put(Constants.RESPONSE,
                        "Deleted the community with id: " + communityId);
            } else {
                logger.error("Invalid communityId: {}", communityId);
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.getParams().setErrMsg(Constants.INVALID_COMMUNITY_ID);
            }

        } catch (Exception e) {
            logger.error("Error while deleting community", communityId, e.getMessage(), e);
            throw new CustomException(Constants.ERROR, "error while processing", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return response;
    }

    @Override
    public ApiResponse update(JsonNode communityDetails, String authToken) {
        log.info("CommunityEngagementService:update:updating community");
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_COMMUNITY_UPDATE);
        validatePayload(Constants.PAYLOAD_VALIDATION_FILE, communityDetails);
        try {
            String userId = accessTokenValidator.verifyUserToken(authToken);
            if (StringUtils.isBlank(userId)) {
                response.getParams().setErrMsg(Constants.USER_ID_DOESNT_EXIST);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                return response;
            }
            if (communityDetails.has(Constants.COMMUNITY_ID) && !communityDetails.get(Constants.COMMUNITY_ID).isNull()) {
                String communityId = communityDetails.get(Constants.COMMUNITY_ID).asText();
                Timestamp currentTime = new Timestamp(System.currentTimeMillis());
                Optional<CommunityEngagementEntity> communityEntityOptional = communityEngagementRepository.findByCommunityIdAndIsActive(communityId, true);
                if (!communityEntityOptional.isPresent()) {
                    response.getParams().setErrMsg(Constants.INVALID_COMMUNITY_ID);
                    response.setResponseCode(HttpStatus.BAD_REQUEST);
                    return response;
                }
                JsonNode dataNode = communityEntityOptional.get().getData();
                Iterator<Map.Entry<String, JsonNode>> fields = communityDetails.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    String fieldName = field.getKey();
                    // Check if the field is present in the update JsonNode
                    if (dataNode.has(fieldName)) {
                        // Update the main JsonNode with the value from the update JsonNode
                        ((ObjectNode) dataNode).set(fieldName, communityDetails.get(fieldName));
                    } else {
                        ((ObjectNode) dataNode).put(fieldName, communityDetails.get(fieldName));
                    }
                }
                communityEntityOptional.get().setUpdatedOn(currentTime);
                ((ObjectNode) dataNode).put(Constants.UPDATED_ON, String.valueOf(currentTime));
                ((ObjectNode) dataNode).put(Constants.UPDATED_BY, userId);
                ((ObjectNode) dataNode).put(Constants.STATUS, Constants.ACTIVE);
                communityEngagementRepository.save(communityEntityOptional.get());
                Map<String, Object> map = objectMapper.convertValue(dataNode, Map.class);
                esUtilService.updateDocument(Constants.INDEX_NAME, Constants.INDEX_TYPE, communityId, map, cbServerProperties.getElasticCommunityJsonPath());
                cacheService.deleteCache(Constants.REDIS_KEY_PREFIX + communityId);
                response.getResult().put(Constants.RESPONSE,
                        "Updated the community with id: " + communityId);
                return response;

            } else {
                response.getParams().setErrMsg(Constants.COMMUNITY_ID_NOT_FOUND);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                return response;
            }


        } catch (Exception e) {
            logger.error("Error while deleting community:", e.getMessage(), e);
            throw new CustomException(Constants.ERROR, "error while processing", HttpStatus.INTERNAL_SERVER_ERROR);

        }
    }

}
