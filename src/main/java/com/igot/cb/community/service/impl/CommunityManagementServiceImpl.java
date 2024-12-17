package com.igot.cb.community.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.authentication.util.AccessTokenValidator;
import com.igot.cb.community.entity.CommunityEntity;
import com.igot.cb.community.repository.CommunityEngagementRepository;
import com.igot.cb.community.service.CommunityManagementService;
import com.igot.cb.pores.cache.CacheService;
import com.igot.cb.pores.elasticsearch.service.EsUtilService;
import com.igot.cb.pores.exceptions.CustomException;
import com.igot.cb.pores.util.*;
import com.igot.cb.transactional.cassandrautils.CassandraOperation;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.ValidationMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.util.CollectionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;


import java.io.InputStream;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.util.CollectionUtils;

/**
 * @author mahesh.vakkund
 */
@Service
@Slf4j
public class CommunityManagementServiceImpl implements CommunityManagementService {

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

    @Autowired
    CassandraOperation cassandraOperation;

    private Logger logger = LoggerFactory.getLogger(CommunityManagementServiceImpl.class);


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
            CommunityEntity communityEngagementEntity = new CommunityEntity();
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
            CommunityEntity saveJsonEntity = communityEngagementRepository.save(communityEngagementEntity);
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
                Optional<CommunityEntity> communityEntityOptional = communityEngagementRepository.findByCommunityIdAndIsActive(communityId, true);
                if (communityEntityOptional.isPresent()) {
                    CommunityEntity communityEntity = communityEntityOptional.get();
                    log.info("Record coming from postgres db");
                    response.getParams().setErrMsg(Constants.SUCCESSFULLY_READING);
                    response.getResult().put(Constants.COMMUNITY_DETAILS, objectMapper.convertValue(communityEntity.getData(), new TypeReference<Object>() {
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
            Optional<CommunityEntity> communityEntityOptional = communityEngagementRepository.findByCommunityIdAndIsActive(communityId, true);
            if (communityEntityOptional.isPresent()) {
                CommunityEntity communityEntity = communityEntityOptional.get();
                communityEntity.setActive(false);
                communityEngagementRepository.save(communityEntity);
                JsonNode esSave = communityEntity.getData();
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
                Optional<CommunityEntity> communityEntityOptional = communityEngagementRepository.findByCommunityIdAndIsActive(communityId, true);
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

    @Override
    public ApiResponse joinAndUnjoinCommunity(Map<String, Object> request, String authToken) {
        log.info("CommunityEngagementService:joinAndUnjoinCommunity:joining");
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_COMMUNITY_UPDATE);
        String error = validateJoinPayload(request);
        if (StringUtils.isNotBlank(error)) {
            return returnErrorMsg(error, HttpStatus.BAD_REQUEST, response);
        }

        try {
            String userId = accessTokenValidator.verifyUserToken(authToken);
            if (StringUtils.isBlank(userId) || userId.equalsIgnoreCase(
                Constants.UNAUTHORIZED_USER)) {
                response.getParams().setErrMsg(Constants.USER_ID_DOESNT_EXIST);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                return response;
            }
            String communityId = (String) request.get(Constants.COMMUNITY_ID);
            Optional<CommunityEntity> optComment = communityEngagementRepository.findByCommunityIdAndIsActive(
                communityId, true);
            if (optComment == null || !optComment.isPresent() || optComment.get().getData()
                .isEmpty()) {
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                response.getParams().setErr(error);
                return response;
            }
            Map<String, Object> propertyMap = new HashMap<>();
            propertyMap.put(Constants.USER_ID, userId);
            propertyMap.put(Constants.CommunityId, communityId);
            List<Map<String, Object>> userCommunityDetails = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                Constants.KEYSPACE_SUNBIRD, Constants.USER_COMMUNITY_TABLE, propertyMap, null, 1);
            if (CollectionUtils.isEmpty(userCommunityDetails)) {
                Map<String, Object> parameterisedMap = new HashMap<>();
                propertyMap.put(Constants.STATUS, true);
                parameterisedMap.put(Constants.COMMUNITY_ID, communityId);
                parameterisedMap.put(Constants.USER_ID, userId);
                parameterisedMap.put(Constants.STATUS, true);
                parameterisedMap.put(Constants.LAST_UPDATED_AT,
                    new Timestamp(Calendar.getInstance().getTime().getTime()));
                cassandraOperation.insertRecord(Constants.KEYSPACE_SUNBIRD,
                    Constants.USER_COMMUNITY_TABLE, parameterisedMap);
                cassandraOperation.insertRecord(Constants.KEYSPACE_SUNBIRD,
                    Constants.USER_COMMUNITY_LOOK_UP_TABLE, propertyMap);
            } else {
                cassandraOperation.deleteRecord(Constants.KEYSPACE_SUNBIRD,
                    Constants.USER_COMMUNITY_TABLE, propertyMap);
                cassandraOperation.deleteRecord(Constants.KEYSPACE_SUNBIRD,
                    Constants.USER_COMMUNITY_LOOK_UP_TABLE, propertyMap);
            }
            return response;

        } catch (Exception e) {
            logger.error("Error while joining community:", e.getMessage(), e);
            throw new CustomException(Constants.ERROR, "error while processing",
                HttpStatus.INTERNAL_SERVER_ERROR);

        }
    }

    @Override
    public ApiResponse communitiesJoinedByUser(String authToken) {
        log.info("CommunityEngagementService:communitiesJoinedByUser:reading");
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_COMMUNITY_UPDATE);
        try {
            String userId = accessTokenValidator.verifyUserToken(authToken);
            if (StringUtils.isBlank(userId) || userId.equalsIgnoreCase(
                Constants.UNAUTHORIZED_USER)) {
                response.getParams().setErrMsg(Constants.USER_ID_DOESNT_EXIST);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                return response;
            }
            Map<String, Object> propertyMap = new HashMap<>();
            propertyMap.put(Constants.USER_ID, userId);
            List<Map<String, Object>> userCommunityDetails = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                Constants.KEYSPACE_SUNBIRD, Constants.USER_COMMUNITY_TABLE, propertyMap,
                Collections.singletonList(Constants.COMMUNITY_ID), null);
            response.getResult().put(Constants.COMMUNITY_DETAILS,
                objectMapper.convertValue(userCommunityDetails, new TypeReference<Object>() {
                }));

            return response;

        } catch (Exception e) {
            logger.error("Error while joining community:", e.getMessage(), e);
            throw new CustomException(Constants.ERROR, "error while processing",
                HttpStatus.INTERNAL_SERVER_ERROR);

        }
    }

    @Override
    public ApiResponse listOfUsersJoined(String communityId, String authToken) {
        log.info("CommunityEngagementService:listOfUsersJoined::reading");
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_COMMUNITY_UPDATE);
        if (StringUtils.isEmpty(communityId)) {
            logger.error("Community Id not found");
            response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
            response.getParams().setErrMsg(Constants.ID_NOT_FOUND);
            return response;
        }
        try {
            String userId = accessTokenValidator.verifyUserToken(authToken);
            if (StringUtils.isBlank(userId) || userId.equalsIgnoreCase(
                Constants.UNAUTHORIZED_USER)) {
                response.getParams().setErrMsg(Constants.USER_ID_DOESNT_EXIST);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                return response;
            }
            Map<String, Object> propertyMap = new HashMap<>();
            propertyMap.put(Constants.COMMUNITY_ID, communityId);
            List<Map<String, Object>> userCommunityDetails = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                Constants.KEYSPACE_SUNBIRD, Constants.USER_COMMUNITY_LOOK_UP_TABLE, propertyMap,
                Collections.singletonList(Constants.USER_ID), null);
            response.getResult().put(Constants.USER_DETAILS,
                objectMapper.convertValue(userCommunityDetails, new TypeReference<Object>() {
                }));

            return response;

        } catch (Exception e) {
            logger.error("Error while reading list of users joined in a  community:",
                e.getMessage(), e);
            throw new CustomException(Constants.ERROR, "error while processing",
                HttpStatus.INTERNAL_SERVER_ERROR);

        }
    }

    private ApiResponse returnErrorMsg(String error, HttpStatus httpStatus, ApiResponse response) {
        response.setResponseCode(httpStatus);
        response.getParams().setErr(error);
        return response;
    }

    private String validateJoinPayload(Map<String, Object> request) {
        StringBuffer str = new StringBuffer();
        List<String> errList = new ArrayList<>();

        if (request.containsKey(Constants.COMMUNITY_ID) &&
            StringUtils.isBlank((String) request.get(Constants.COMMUNITY_ID))) {
            errList.add(Constants.COMMUNITY_ID);
        }
        if (!errList.isEmpty()) {
            str.append("Failed Due To Missing Params - ").append(errList).append(".");
        }
        return str.toString();
    }

}
