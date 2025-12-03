package com.igot.cb.discussion.service.impl;

import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.authentication.util.AccessTokenValidator;
import com.igot.cb.discussion.entity.DiscussionAnswerPostReplyEntity;
import com.igot.cb.discussion.entity.DiscussionEntity;
import com.igot.cb.discussion.repository.DiscussionAnswerPostReplyRepository;
import com.igot.cb.discussion.repository.DiscussionRepository;
import com.igot.cb.discussion.service.AnswerPostReplyService;
import com.igot.cb.notificationUtill.HelperMethodService;
import com.igot.cb.notificationUtill.NotificationTriggerService;
import com.igot.cb.pores.cache.CacheService;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.elasticsearch.dto.SearchResult;
import com.igot.cb.pores.elasticsearch.service.EsUtilService;
import com.igot.cb.pores.util.*;
import com.igot.cb.producer.Producer;
import com.igot.cb.profanity.IProfanityCheckService;
import com.igot.cb.transactional.cassandrautils.CassandraOperation;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.igot.cb.pores.util.Constants.*;

@Service
@Slf4j
public class AnswerPostReplyServiceImpl implements AnswerPostReplyService {


    @Autowired
    private PayloadValidation payloadValidation;
    @Autowired
    private DiscussionRepository discussionRepository;
    @Autowired
    private CacheService cacheService;
    @Autowired
    private EsUtilService esUtilService;
    @Autowired
    private CbServerProperties cbServerProperties;
    @Autowired
    private AccessTokenValidator accessTokenValidator;
    @Autowired
    private DiscussionAnswerPostReplyRepository discussionAnswerPostReplyRepository;
    @Autowired
    private CassandraOperation cassandraOperation;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private NotificationTriggerService notificationTriggerService;
    @Autowired
    private HelperMethodService helperMethodService;
    @Autowired
    @Qualifier(Constants.SEARCH_RESULT_REDIS_TEMPLATE)
    private RedisTemplate<String, SearchResult> redisTemplate;

    @Autowired
    private IProfanityCheckService profanityCheckService;

    @Autowired
    private Producer producer;

    @Autowired
    private DiscussionServiceUtil discussionServiceUtil;

    @Override
    public ApiResponse createAnswerPostReply(JsonNode answerPostDataReplyData, String token) {
        log.info("DiscussionService::createAnswerPostReply:creating answerPostReply");
        ApiResponse response = ProjectUtil.createDefaultResponse("discussion.createAnswerPost");
        payloadValidation.validatePayload(Constants.ANSWER_POST_REPLY_VALIDATION_SCHEMA, answerPostDataReplyData);
        String userId = accessTokenValidator.verifyUserToken(token);
        if (StringUtils.isBlank(userId) || userId.equals(Constants.UNAUTHORIZED)) {
            response.getParams().setErrMsg(Constants.INVALID_AUTH_TOKEN);
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return response;
        }
        DiscussionEntity discussionEntity = discussionRepository.findById(answerPostDataReplyData.get(Constants.PARENT_ANSWER_POST_ID).asText()).orElse(null);
        if (discussionEntity == null || !discussionEntity.getIsActive()) {
            return ProjectUtil.returnErrorMsg(Constants.INVALID_PARENT_ANSWER_POST_ID, HttpStatus.BAD_REQUEST, response, Constants.FAILED);
        }
        JsonNode data = discussionEntity.getData();
        String type = data.get(Constants.TYPE).asText();
        if (!type.equals(Constants.ANSWER_POST)) {
            return ProjectUtil.returnErrorMsg("parentAnswerPostReplyId must be of type answerPost", HttpStatus.BAD_REQUEST, response, Constants.FAILED);
        }
        if (data.get(Constants.STATUS).asText().equals(Constants.SUSPENDED)) {
            return ProjectUtil.returnErrorMsg(Constants.PARENT_DISCUSSION_ID_ERROR, HttpStatus.BAD_REQUEST, response, Constants.FAILED);
        }
        if (!answerPostDataReplyData.get(Constants.COMMUNITY_ID).asText().equals(data.get(Constants.COMMUNITY_ID).asText())) {
            response.getParams().setErrMsg(Constants.INVALID_COMMUNITY_ID);
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return response;
        }

        try {
            JsonNode mentionedUsersNode = answerPostDataReplyData.get(MENTIONED_USERS);
            List<String> userIdList = new ArrayList<>();
            if (mentionedUsersNode != null && mentionedUsersNode.isArray() && mentionedUsersNode.size() > 0) {
                Map<String, JsonNode> uniqueUserMap = new LinkedHashMap<>();
                mentionedUsersNode.forEach(node -> {
                    String userid = node.path(USER_ID_RQST).asText(null);
                    if (StringUtils.isNotBlank(userid) && !uniqueUserMap.containsKey(userid)) {
                        uniqueUserMap.put(userid, node);
                    }
                });
                ArrayNode cleanArray = objectMapper.createArrayNode();
                uniqueUserMap.values().forEach(cleanArray::add);
                ((ObjectNode) answerPostDataReplyData).set(MENTIONED_USERS, cleanArray);
                userIdList.addAll(uniqueUserMap.keySet());
            }
            ObjectNode answerPostReplyDataNode = (ObjectNode) answerPostDataReplyData;
            Map<String, Object> propertyMap = new HashMap<>();
            propertyMap.put(Constants.USERID, userId);
            propertyMap.put(Constants.COMMUNITY_ID, answerPostReplyDataNode.get(Constants.COMMUNITY_ID).asText());
            List<Map<String, Object>> communityDetails = cassandraOperation.getRecordsByPropertiesWithoutFiltering(Constants.KEYSPACE_SUNBIRD, Constants.USER_COMMUNITY, propertyMap, Arrays.asList(Constants.STATUS), null);
            if (communityDetails.isEmpty() || !(boolean) communityDetails.get(0).get(Constants.STATUS)) {
                DiscussionServiceUtil.createErrorResponse(response, Constants.USER_NOT_PART_OF_COMMUNITY, HttpStatus.BAD_REQUEST, Constants.FAILED);
                return response;
            }
            answerPostReplyDataNode.put(Constants.CREATED_BY, userId);
            answerPostReplyDataNode.put(Constants.VOTE_COUNT, 0);
            answerPostReplyDataNode.put(Constants.STATUS, Constants.ACTIVE);
            answerPostReplyDataNode.put(Constants.PARENT_ANSWER_POST_ID, answerPostDataReplyData.get(Constants.PARENT_ANSWER_POST_ID));

            DiscussionAnswerPostReplyEntity jsonNodeEntity = new DiscussionAnswerPostReplyEntity();

            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            UUID id = Uuids.timeBased();
            answerPostReplyDataNode.put(Constants.DISCUSSION_ID, String.valueOf(id));
            jsonNodeEntity.setDiscussionId(String.valueOf(id));
            jsonNodeEntity.setCreatedOn(currentTime);
            answerPostReplyDataNode.put(Constants.CREATED_ON, DiscussionServiceUtil.getFormattedCurrentTime(currentTime));
            answerPostReplyDataNode.put(Constants.UPDATED_ON, DiscussionServiceUtil.getFormattedCurrentTime(currentTime));
            jsonNodeEntity.setIsActive(true);
            answerPostReplyDataNode.put(Constants.IS_ACTIVE, true);
            jsonNodeEntity.setData(answerPostReplyDataNode);
            jsonNodeEntity.setCreatedOn(currentTime);
            jsonNodeEntity.setUpdatedOn(currentTime);
            jsonNodeEntity.setIsProfane(false);
            discussionAnswerPostReplyRepository.save(jsonNodeEntity);

            ObjectNode jsonNode = objectMapper.createObjectNode();
            jsonNode.setAll(answerPostReplyDataNode);
            Map<String, Object> discussionAnswerPostReplyDetailsMap = objectMapper.convertValue(jsonNode, Map.class);
            discussionAnswerPostReplyDetailsMap.put(Constants.IS_PROFANE, false);
            esUtilService.addDocument(cbServerProperties.getDiscussionEntity(), String.valueOf(id), discussionAnswerPostReplyDetailsMap, cbServerProperties.getElasticDiscussionJsonPath());
            cacheService.putCache(Constants.DISCUSSION_CACHE_PREFIX + id, jsonNode);
            updateAnswerPostReplyToAnswerPost(discussionEntity, String.valueOf(id), Constants.INCREMENT);
            redisTemplate.opsForValue()
                    .getAndDelete(discussionServiceUtil.generateRedisJwtTokenKey(createDefaultSearchCriteria(
                            answerPostReplyDataNode.get(Constants.PARENT_ANSWER_POST_ID).asText(),
                            answerPostReplyDataNode.get(Constants.COMMUNITY_ID).asText())));
            redisTemplate.opsForValue()
                    .getAndDelete(discussionServiceUtil.generateRedisJwtTokenKey(createSearchCriteriaWithDefaults(
                            answerPostReplyDataNode.get(Constants.PARENT_DISCUSSION_ID).asText(),
                            answerPostReplyDataNode.get(Constants.COMMUNITY_ID).asText(),
                            Constants.ANSWER_POST)));
            log.info("AnswerPostReply post created successfully");
            discussionAnswerPostReplyDetailsMap.put(Constants.CREATED_ON, currentTime);
            response.setResponseCode(HttpStatus.CREATED);
            response.getParams().setStatus(Constants.SUCCESS);
            response.setResult(discussionAnswerPostReplyDetailsMap);
            try {
                Map<String, Object> notificationData = Map.of(
                        Constants.COMMUNITY_ID, answerPostReplyDataNode.get(Constants.COMMUNITY_ID).asText(),
                        Constants.DISCUSSION_ID, answerPostReplyDataNode.get(Constants.PARENT_DISCUSSION_ID).asText()
                );

                String discussionOwner = discussionEntity.getData().get(Constants.CREATED_BY).asText();
                String createdBy = answerPostReplyDataNode.get(Constants.CREATED_BY).asText();
                String firstName = helperMethodService.fetchUserFirstName(createdBy);
                log.info("Notification trigger started for create answerPost");
                if (!userId.equals(discussionOwner)) {
                    notificationTriggerService.triggerNotification(REPLIED_COMMENT, ENGAGEMENT, List.of(discussionOwner), TITLE, firstName, notificationData);
                }
                if (CollectionUtils.isNotEmpty(userIdList)) {
                    List<String> filteredUserIdList = userIdList.stream()
                            .filter(uniqueId -> !uniqueId.equals(userId))
                            .toList();
                    if (CollectionUtils.isNotEmpty(filteredUserIdList)) {
                        Map<String, Object> replyNotificationData = Map.of(
                                Constants.COMMUNITY_ID, answerPostReplyDataNode.get(Constants.COMMUNITY_ID).asText(),
                                Constants.DISCUSSION_ID, answerPostReplyDataNode.get(Constants.PARENT_DISCUSSION_ID).asText()
                        );

                        notificationTriggerService.triggerNotification(TAGGED_COMMENT, ENGAGEMENT, filteredUserIdList, TITLE, firstName, replyNotificationData);
                    }
                }
            } catch (Exception e) {
                log.error("Error while triggering notification", e);
            }
            producer.push(cbServerProperties.getKafkaProcessDetectLanguageTopic(), answerPostReplyDataNode);
        } catch (Exception e) {
            log.error("Failed to create AnswerPost: {}", e.getMessage(), e);
            DiscussionServiceUtil.createErrorResponse(response, Constants.FAILED_TO_CREATE_ANSWER_POST_REPLY, HttpStatus.INTERNAL_SERVER_ERROR, Constants.FAILED);
            return response;
        }
        return response;
    }

    public SearchCriteria createDefaultSearchCriteria(String parentAnswerPostId,
                                                       String communityId) {
        SearchCriteria criteria = new SearchCriteria();
        HashMap<String, Object> filterMap = new HashMap<>();
        filterMap.put(Constants.COMMUNITY_ID, communityId);
        filterMap.put(Constants.TYPE, Constants.ANSWER_POST_REPLY);
        filterMap.put(Constants.PARENT_ANSWER_POST_ID, parentAnswerPostId);
        criteria.setFilterCriteriaMap(filterMap);
        criteria.setRequestedFields(Collections.emptyList());
        criteria.setPageNumber(0);
        criteria.setPageSize(10);
        criteria.setOrderBy(Constants.CREATED_ON);
        criteria.setOrderDirection(Constants.DESC);
        criteria.setFacets(Collections.emptyList());
        return criteria;
    }

    public void updateAnswerPostReplyToAnswerPost(DiscussionEntity discussionEntity, String discussionId, String action) {
        log.info("DiscussionService::updateAnswerPostReplyToAnswerPost:inside");
        JsonNode data = discussionEntity.getData();
        Set<String> answerPostReplies = new HashSet<>();

        if (data.has(Constants.ANSWER_POST_REPLIES)) {
            ArrayNode existingAnswerPostReplies = (ArrayNode) data.get(Constants.ANSWER_POST_REPLIES);
            existingAnswerPostReplies.forEach(post -> answerPostReplies.add(post.asText()));
        }
        if (Constants.INCREMENT.equals(action)) {
            answerPostReplies.add(discussionId);
        } else {
            answerPostReplies.remove(discussionId);
        }

        ArrayNode arrayNode = objectMapper.valueToTree(answerPostReplies);
        ((ObjectNode) data).put(Constants.ANSWER_POST_REPLIES, arrayNode);
        ((ObjectNode) data).put(Constants.ANSWER_POST_REPLIES_COUNT, answerPostReplies.size());

        discussionEntity.setData(data);
        DiscussionEntity savedEntity = discussionRepository.save(discussionEntity);
        log.info("DiscussionService::updateAnswerPostReplyToAnswerPost: DiscussionEntity updated successfully");
        ObjectNode jsonNode = objectMapper.createObjectNode();
        jsonNode.setAll((ObjectNode) savedEntity.getData());
        Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
        map.put(IS_PROFANE, Boolean.TRUE.equals(discussionEntity.getIsProfane()));
        esUtilService.updateDocument(cbServerProperties.getDiscussionEntity(), discussionEntity.getDiscussionId(), map, cbServerProperties.getElasticDiscussionJsonPath());
        cacheService.putCache(Constants.DISCUSSION_CACHE_PREFIX + discussionEntity.getDiscussionId(), jsonNode);
    }

    @Override
    public ApiResponse readAnswerPostReply(String discussionId) {
        log.info("reading readAnswerPostReply details");
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.ANSWER_POST_REPLY_READ_API);
        if (StringUtils.isBlank(discussionId)) {
            log.error("AnswerPostReply not found");
            DiscussionServiceUtil.createErrorResponse(response, Constants.ID_NOT_FOUND, HttpStatus.BAD_REQUEST, Constants.FAILED);
            return response;
        }
        try {
            String cachedJson = cacheService.getCache(Constants.DISCUSSION_CACHE_PREFIX + discussionId);
            if (!StringUtils.isBlank(cachedJson)) {
                log.info("AnswerPostReply Record coming from redis cache");
                response.setMessage(Constants.SUCCESS);
                response.setResponseCode(HttpStatus.OK);
                response.setResult((Map<String, Object>) objectMapper.readValue(cachedJson, new TypeReference<Object>() {
                }));
            } else {
                Optional<DiscussionAnswerPostReplyEntity> entityOptional = discussionAnswerPostReplyRepository.findById(discussionId);
                if (entityOptional.isPresent()) {
                    DiscussionAnswerPostReplyEntity discussionEntity = entityOptional.get();
                    cacheService.putCache(Constants.DISCUSSION_CACHE_PREFIX + discussionId, discussionEntity.getData());
                    log.info("AnswerPostReply Record coming from postgres db");
                    response.setMessage(Constants.SUCCESS);
                    response.setResponseCode(HttpStatus.OK);
                    response.setResult((Map<String, Object>) objectMapper.convertValue(discussionEntity.getData(), new TypeReference<Object>() {
                    }));
                    response.getResult().put(Constants.IS_ACTIVE, discussionEntity.getIsActive());
                    response.getResult().put(Constants.CREATED_ON, discussionEntity.getCreatedOn());
                    response.getResult().put(Constants.UPDATED_ON, discussionEntity.getUpdatedOn());
                } else {
                    log.error("Invalid AnswerPostReply discussionId: {}", discussionId);
                    DiscussionServiceUtil.createErrorResponse(response, Constants.INVALID_ID, HttpStatus.NOT_FOUND, Constants.FAILED);
                    return response;
                }
            }
        } catch (Exception e) {
            log.error(" JSON for AnswerPostReplyId {}: {}", discussionId, e.getMessage(), e);
            DiscussionServiceUtil.createErrorResponse(response, "Failed to read the AnswerPostReply", HttpStatus.INTERNAL_SERVER_ERROR, Constants.FAILED);
            return response;
        }
        return response;
    }

    @Override
    public ApiResponse deleteAnswerPostReply(String discussionId, String type, String token) {
        log.info("DiscussionServiceImpl::delete AnswerPostReply");
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.DELETE_ANSWER_POST_REPLY_API);
        String userId = accessTokenValidator.verifyUserToken(token);
        if (StringUtils.isBlank(userId)) {
            DiscussionServiceUtil.createErrorResponse(response, Constants.INVALID_AUTH_TOKEN, HttpStatus.BAD_REQUEST, Constants.FAILED);
            return response;
        }

        if (StringUtils.isBlank(discussionId)) {
            DiscussionServiceUtil.createErrorResponse(response, Constants.ID_NOT_FOUND, HttpStatus.BAD_REQUEST, Constants.NO_DATA_FOUND);
            return response;
        }

        try {
            Optional<DiscussionAnswerPostReplyEntity> entityOptional = discussionAnswerPostReplyRepository.findById(discussionId);
            if (!entityOptional.isPresent()) {
                DiscussionServiceUtil.createErrorResponse(response, Constants.INVALID_ID, HttpStatus.BAD_REQUEST, Constants.NO_DATA_FOUND);
                return response;
            }
            DiscussionAnswerPostReplyEntity jasonEntity = entityOptional.get();
            if (Boolean.FALSE.equals(jasonEntity.getIsActive())) {
                log.info("AnswerPostReply is already inactive.");
                DiscussionServiceUtil.createErrorResponse(response, Constants.DISCUSSION_IS_INACTIVE, HttpStatus.ALREADY_REPORTED, Constants.SUCCESS);
                return response;
            }
            JsonNode data = jasonEntity.getData();
            if (!type.equals(data.get(Constants.TYPE).asText())) {
                DiscussionServiceUtil.createErrorResponse(response, Constants.INVALID_TYPE + type, HttpStatus.BAD_REQUEST, Constants.FAILED);
                return response;
            }

            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            jasonEntity.setIsActive(false);
            jasonEntity.setUpdatedOn(currentTime);
            ((ObjectNode) data).put(Constants.IS_ACTIVE, false);
            ((ObjectNode) data).put(Constants.UPDATED_ON, String.valueOf(currentTime));
            jasonEntity.setData(data);
            jasonEntity.setDiscussionId(discussionId);
            jasonEntity.setUpdatedOn(currentTime);
            discussionAnswerPostReplyRepository.save(jasonEntity);
            Map<String, Object> map = objectMapper.convertValue(data, Map.class);
            map.put(Constants.IS_ACTIVE, false);
            DiscussionEntity discussionEntity = discussionRepository.findById(data.get(Constants.PARENT_ANSWER_POST_ID).asText()).orElse(null);
            updateAnswerPostReplyToAnswerPost(discussionEntity, discussionId, Constants.DECREMENT);
            esUtilService.updateDocument(cbServerProperties.getDiscussionEntity(), discussionId, map, cbServerProperties.getElasticDiscussionJsonPath());
            cacheService.putCache(Constants.DISCUSSION_CACHE_PREFIX + discussionId, data);
            log.info("AnswerPostReply details deleted successfully");
            response.setResponseCode(HttpStatus.OK);
            response.setMessage(Constants.DELETED_SUCCESSFULLY);
            response.getParams().setStatus(Constants.SUCCESS);
            redisTemplate.opsForValue().getAndDelete(discussionServiceUtil.generateRedisJwtTokenKey(createDefaultSearchCriteria(data.get(Constants.PARENT_ANSWER_POST_ID).asText(), data.get(Constants.COMMUNITY_ID).asText())));
            redisTemplate.opsForValue()
                    .getAndDelete(discussionServiceUtil.generateRedisJwtTokenKey(createSearchCriteriaWithDefaults(
                            data.get(Constants.PARENT_DISCUSSION_ID).asText(),
                            data.get(Constants.COMMUNITY_ID).asText(),
                            Constants.ANSWER_POST)));
            return response;
        } catch (Exception e) {
            log.error("Error while deleting discussion with ID: {}. Exception: {}", discussionId, e.getMessage(), e);
            DiscussionServiceUtil.createErrorResponse(response, Constants.FAILED_TO_DELETE_DISCUSSION, HttpStatus.INTERNAL_SERVER_ERROR, Constants.FAILED);
            return response;
        }
    }

    @Override
    public ApiResponse updateAnswerPostReply(JsonNode answerPostReplyData, String token) {
        log.info("DiscussionService::updateAnswerPostReply:updating answerPostReply");
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.ANSWER_POST_REPLY_UPDATE_API);
        payloadValidation.validatePayload(Constants.ANSWER_POST_REPLY_UPDATE_VALIDATION_SCHEMA, answerPostReplyData);
        String userId = accessTokenValidator.verifyUserToken(token);
        if (StringUtils.isBlank(userId) || userId.equals(Constants.UNAUTHORIZED)) {
            response.getParams().setErrMsg(Constants.INVALID_AUTH_TOKEN);
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return response;
        }

        DiscussionAnswerPostReplyEntity discussionAnswerPostReplyEntity = discussionAnswerPostReplyRepository.findById(answerPostReplyData.get(Constants.ANSWER_POST_REPLY_ID).asText()).orElse(null);
        if (discussionAnswerPostReplyEntity == null || !discussionAnswerPostReplyEntity.getIsActive()) {
            return ProjectUtil.returnErrorMsg(Constants.INVALID_ANSWER_POST_REPLY_ID, HttpStatus.BAD_REQUEST, response, Constants.FAILED);
        }
        ObjectNode data = (ObjectNode) discussionAnswerPostReplyEntity.getData();
        String type = data.get(Constants.TYPE).asText();
        if (!type.equals(Constants.ANSWER_POST_REPLY)) {
            return ProjectUtil.returnErrorMsg(Constants.INVALID_ANSWER_POST_REPLY_ID, HttpStatus.BAD_REQUEST, response, Constants.FAILED);
        }
        if (data.get(Constants.STATUS).asText().equals(Constants.SUSPENDED)) {
            return ProjectUtil.returnErrorMsg(Constants.POST_ALREADY_SUSPENDED, HttpStatus.BAD_REQUEST, response, Constants.FAILED);
        }

        try {
            ObjectNode answerPostReplyDataNode = (ObjectNode) answerPostReplyData;

            Set<String> existingMentionedUserIds = new HashSet<>();
            data.withArray(MENTIONED_USERS).forEach(userNode -> {
                String userid = userNode.path(USER_ID_RQST).asText(null);
                if (StringUtils.isNotBlank(userid)) existingMentionedUserIds.add(userid);
            });
            Set<String> seenUserIdsInRequest = new HashSet<>();
            List<String> newlyAddedUserIds = new ArrayList<>();
            ArrayNode uniqueMentionedUsers = objectMapper.createArrayNode();
            JsonNode incomingMentionedUsers = answerPostReplyData.path(MENTIONED_USERS);
            if (incomingMentionedUsers.isArray()) {
                for (JsonNode userNode : incomingMentionedUsers) {
                    String userid = userNode.path(USER_ID_RQST).asText(null);
                    if (StringUtils.isNotBlank(userid) && seenUserIdsInRequest.add(userid)) {
                        uniqueMentionedUsers.add(userNode);
                        if (!existingMentionedUserIds.contains(userid)) {
                            newlyAddedUserIds.add(userid);
                        }
                    }
                }
            }

            answerPostReplyDataNode.set(MENTIONED_USERS, uniqueMentionedUsers);

            answerPostReplyDataNode.remove(Constants.ANSWER_POST_REPLY_ID);
            if (!answerPostReplyDataNode.has(Constants.IS_INITIAL_UPLOAD) || !answerPostReplyDataNode.get(Constants.IS_INITIAL_UPLOAD).asBoolean()) {
                Timestamp currentTime = new Timestamp(System.currentTimeMillis());
                answerPostReplyDataNode.put(Constants.UPDATED_ON, DiscussionServiceUtil.getFormattedCurrentTime(currentTime));
                discussionAnswerPostReplyEntity.setUpdatedOn(currentTime);
            }
            data.setAll(answerPostReplyDataNode);
            discussionAnswerPostReplyEntity.setData(data);
            discussionAnswerPostReplyEntity.setIsProfane(false);
            discussionAnswerPostReplyRepository.save(discussionAnswerPostReplyEntity);

            ObjectNode jsonNode = objectMapper.createObjectNode();
            jsonNode.setAll(data);
            Map<String, Object> discussionAnswerPostReplyDetailsMap = objectMapper.convertValue(jsonNode, Map.class);
            discussionAnswerPostReplyDetailsMap.put(Constants.IS_PROFANE, false);
            esUtilService.updateDocument(cbServerProperties.getDiscussionEntity(), discussionAnswerPostReplyEntity.getDiscussionId(), discussionAnswerPostReplyDetailsMap, cbServerProperties.getElasticDiscussionJsonPath());
            cacheService.putCache(Constants.DISCUSSION_CACHE_PREFIX + discussionAnswerPostReplyEntity.getDiscussionId(), jsonNode);
            redisTemplate.opsForValue()
                    .getAndDelete(discussionServiceUtil.generateRedisJwtTokenKey(createDefaultSearchCriteria(
                            data.get(Constants.PARENT_ANSWER_POST_ID).asText(),
                            data.get(Constants.COMMUNITY_ID).asText())));
            log.info("AnswerPostReply updated successfully");
            try {
                if (CollectionUtils.isNotEmpty(newlyAddedUserIds)) {
                    Map<String, Object> notificationData = Map.of(
                            Constants.COMMUNITY_ID, data.get(Constants.COMMUNITY_ID).asText(),
                            Constants.DISCUSSION_ID, data.get(Constants.PARENT_DISCUSSION_ID).asText()
                    );
                    String firstName = helperMethodService.fetchUserFirstName(userId);
                    notificationTriggerService.triggerNotification(TAGGED_COMMENT, ENGAGEMENT, newlyAddedUserIds, TITLE, firstName, notificationData);
                }
            } catch (Exception e) {
                log.error("Error while triggering notification for update answerPostReply", e);
            }
            discussionAnswerPostReplyDetailsMap.remove(IS_PROFANE);
            discussionAnswerPostReplyDetailsMap.remove(Constants.PROFANITY_RESPONSE);
            response.setResponseCode(HttpStatus.OK);
            response.getParams().setStatus(Constants.SUCCESS);
            response.setResult(discussionAnswerPostReplyDetailsMap);
            producer.push(cbServerProperties.getKafkaProcessDetectLanguageTopic(), jsonNode);
        } catch (Exception e) {
            log.error("Failed to update AnswerPost: {}", e.getMessage(), e);
            DiscussionServiceUtil.createErrorResponse(response, Constants.FAILED_TO_UPDATE_ANSWER_POST, HttpStatus.INTERNAL_SERVER_ERROR, Constants.FAILED);
            return response;
        }
        return response;
    }

    public SearchCriteria createSearchCriteriaWithDefaults(String parentDiscussionId,
                                                            String communityId,
                                                            String type) {
        SearchCriteria criteria = new SearchCriteria();

        HashMap<String, Object> filterMap = new HashMap<>();
        filterMap.put(Constants.COMMUNITY_ID, communityId);
        filterMap.put(Constants.TYPE, type);
        filterMap.put(Constants.PARENT_DISCUSSION_ID, parentDiscussionId);
        criteria.setFilterCriteriaMap(filterMap);
        criteria.setRequestedFields(Collections.emptyList());
        criteria.setPageNumber(0);
        criteria.setPageSize(10);
        criteria.setOrderBy(Constants.CREATED_ON);
        criteria.setOrderDirection(Constants.DESC);
        criteria.setFacets(Collections.emptyList());
        return criteria;
    }

    @Override
    public ApiResponse managePost(Map<String, Object> reportData, String token, String action) {
        log.info("DiscussionServiceImpl::managePost");
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.ADMIN_MANAGE_POST_API);

        String userId = accessTokenValidator.verifyUserToken(token);
        if (StringUtils.isBlank(userId) || Constants.UNAUTHORIZED.equals(userId)) {
            return ProjectUtil.returnErrorMsg(Constants.INVALID_AUTH_TOKEN, HttpStatus.BAD_REQUEST, response, Constants.FAILED);
        }

        String errorMsg = validateSuspendPostPayload(reportData);
        if (StringUtils.isNotEmpty(errorMsg)) {
            return ProjectUtil.returnErrorMsg(errorMsg, HttpStatus.BAD_REQUEST, response, Constants.FAILED);
        }

        String discussionId = (String) reportData.get(Constants.DISCUSSION_ID);
        String type = (String) reportData.get(Constants.TYPE);

        try {
            Object entity = Constants.ANSWER_POST_REPLY.equals(type)
                    ? discussionAnswerPostReplyRepository.findById(discussionId).orElse(null)
                    : discussionRepository.findById(discussionId).orElse(null);
            if (entity == null) {
                return ProjectUtil.returnErrorMsg(Constants.DISCUSSION_NOT_FOUND, HttpStatus.NOT_FOUND, response, Constants.FAILED);
            }

            JsonNode dataNode;
            Boolean isActive;
            if (Constants.ANSWER_POST_REPLY.equals(type)) {
                DiscussionAnswerPostReplyEntity reply = (DiscussionAnswerPostReplyEntity) entity;
                dataNode = reply.getData();
                isActive = reply.getIsActive();
            } else {
                DiscussionEntity discussion = (DiscussionEntity) entity;
                dataNode = discussion.getData();
                isActive = discussion.getIsActive();
            }

            ObjectNode data = (ObjectNode) dataNode;
            if (!isActive) {
                return ProjectUtil.returnErrorMsg(Constants.DISCUSSION_IS_INACTIVE, HttpStatus.CONFLICT, response, Constants.FAILED);
            }
            if (data.get(Constants.STATUS).asText().equals(Constants.ACTIVE) && action.equals(Constants.SUSPEND)) {
                return ProjectUtil.returnErrorMsg(Constants.POST_IS_ACTIVE_MSG, HttpStatus.BAD_REQUEST, response, Constants.FAILED);
            }

            if (data.get(Constants.STATUS).asText().equals(Constants.SUSPENDED) && action.equals(Constants.SUSPEND) ||
                    data.get(Constants.STATUS).asText().equals(Constants.ACTIVE) && action.equals(Constants.ACTIVE)) {
                return ProjectUtil.returnErrorMsg(Constants.POST_ERROR_MSG + data.get(Constants.STATUS).asText() + ".", HttpStatus.BAD_REQUEST, response, Constants.FAILED);
            }
            String status = data.get(Constants.STATUS).asText();
            if (Constants.SUSPEND.equals(action)) {
                data.put(Constants.STATUS, Constants.SUSPENDED);
            } else if (Constants.ACTIVE.equals(action)) {
                data.put(Constants.STATUS, Constants.ACTIVE);
            }

            data.put(Constants.UPDATED_ON, DiscussionServiceUtil.getFormattedCurrentTime(new Timestamp(System.currentTimeMillis())));
            data.put(Constants.UPDATED_BY, userId);

            if (Constants.ACTIVE.equals(action)) {
                Map<String, Object> propertyMap = new HashMap<>();
                propertyMap.put(Constants.DISCUSSION_ID, discussionId);
                List<Map<String, Object>> reportUsers = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                        Constants.KEYSPACE_SUNBIRD, Constants.DISCUSSION_POST_REPORT_LOOKUP_BY_POST,
                        propertyMap, Arrays.asList(Constants.USERID), null
                );

                for (Map<String, Object> map : reportUsers) {
                    Map<String, Object> keyMap = new HashMap<>();
                    keyMap.put(Constants.DISCUSSION_ID, discussionId);
                    keyMap.put(Constants.USERID, map.get(Constants.USERID));
                    cassandraOperation.deleteRecord(Constants.KEYSPACE_SUNBIRD, Constants.DISCUSSION_POST_REPORT_LOOKUP_BY_USER, keyMap);
                    log.info("Deleted report record for user: {}, by admin: {}", map.get(Constants.USERID), userId);
                }

                cassandraOperation.deleteRecord(Constants.KEYSPACE_SUNBIRD, Constants.DISCUSSION_POST_REPORT_LOOKUP_BY_POST, propertyMap);
            }

            if (Constants.ANSWER_POST_REPLY.equals(type)) {
                ((DiscussionAnswerPostReplyEntity) entity).setData(dataNode);
                discussionAnswerPostReplyRepository.save((DiscussionAnswerPostReplyEntity) entity);
            } else {
                ((DiscussionEntity) entity).setData(dataNode);
                discussionRepository.save((DiscussionEntity) entity);
            }

            ObjectNode jsonNode = objectMapper.createObjectNode().setAll(data);
            Map<String, Object> esMap = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(
                    cbServerProperties.getDiscussionEntity(), discussionId,
                    esMap, cbServerProperties.getElasticDiscussionJsonPath()
            );

            Map<String, String> cachePrefixes = new HashMap<>();
            cachePrefixes.put(Constants.SUSPEND, Constants.SUSPENDED_POSTS_CACHE_PREFIX);
            cachePrefixes.put(Constants.ANSWER_POST, Constants.REPORTED_ANSWER_POST_POSTS_CACHE_PREFIX);
            cachePrefixes.put(Constants.QUESTION, Constants.REPORTED_QUESTION_POSTS_CACHE_PREFIX);
            cachePrefixes.put(Constants.ANSWER_POST_REPLY, Constants.REPORTED_ANSWER_POST_REPLY_POSTS_CACHE_PREFIX);

            String communityId = data.get(Constants.COMMUNITY_ID).asText();
            deleteCacheByPrefix(Constants.ALL_REPORTED_POSTS_CACHE_PREFIX + communityId);

            if (cachePrefixes.containsKey(action)) {
                deleteCacheByPrefix(cachePrefixes.get(action) + communityId);
            }
            if (cachePrefixes.containsKey(type)) {
                deleteCacheByPrefix(cachePrefixes.get(type) + communityId);
            }
            if (Constants.ACTIVE.equals(action) && Constants.SUSPENDED.equals(status)) {
                deleteCacheByPrefix(cachePrefixes.get(Constants.SUSPEND) + communityId);
            }
            cacheService.putCache(Constants.DISCUSSION_CACHE_PREFIX + discussionId, jsonNode);
        } catch (Exception e) {
            log.error("Failed to suspend post: {}", e.getMessage(), e);
            DiscussionServiceUtil.createErrorResponse(response, Constants.FAILED, HttpStatus.INTERNAL_SERVER_ERROR, Constants.FAILED);
            return response;
        }
        return response;
    }

    private String validateSuspendPostPayload(Map<String, Object> reportData) {
        StringBuilder errorMsg = new StringBuilder();
        List<String> errList = new ArrayList<>();

        if (reportData == null) {
            errorMsg.append("Failed Due To Missing Params - ").append(Constants.DISCUSSION_ID).append(",").append(Constants.TYPE).append(".");
            return errorMsg.toString();
        }

        if (StringUtils.isBlank((String) reportData.get(Constants.DISCUSSION_ID))) {
            errList.add(Constants.DISCUSSION_ID);
        }
        if (StringUtils.isBlank((String) reportData.get(Constants.TYPE))) {
            errList.add(Constants.TYPE);
        } else if (!Constants.ANSWER_POST.equalsIgnoreCase((String) reportData.get(Constants.TYPE)) &&
                !Constants.QUESTION.equalsIgnoreCase((String) reportData.get(Constants.TYPE)) &&
                !Constants.ANSWER_POST_REPLY.equalsIgnoreCase((String) reportData.get(Constants.TYPE))) {
            errList.add("type must be either 'question' or 'AnswerPost' or 'AnswerPostReply'");
        }

        if (!errList.isEmpty()) {
            errorMsg.append("Failed Due To Missing Params - ").append(errList).append(".");
        }
        return errorMsg.toString();
    }

    @Override
    public ApiResponse getReportStatistics(Map<String, Object> getReportData) {
        log.info("DiscussionServiceImpl::getReportStatistics");
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.GET_REPORT_STATISTICS_API);

        String errorMsg = validateSuspendPostPayload(getReportData);
        if (StringUtils.isNotEmpty(errorMsg)) {
            return ProjectUtil.returnErrorMsg(errorMsg, HttpStatus.BAD_REQUEST, response, Constants.FAILED);
        }

        try {
            String discussionId = (String) getReportData.get(Constants.DISCUSSION_ID);
            String redisKey = Constants.REPORT_STATISTICS_CACHE_PREFIX + discussionId;
            String cachedStatistics = cacheService.getCache(redisKey);
            if (StringUtils.isNotBlank(cachedStatistics)) {
                log.info("Returning cached report statistics for discussionId: {}", discussionId);
                response.setResult(objectMapper.readValue(cachedStatistics, new TypeReference<Map<String, Object>>() {
                }));
                return response;
            }

            String validReasonsKey = Constants.VALID_REASONS_CACHE_KEY;
            Set<String> validReasons = null;
            String cachedValidReasons = cacheService.getCache(validReasonsKey);
            if (StringUtils.isNotBlank(cachedValidReasons)) {
                validReasons = objectMapper.readValue(cachedValidReasons, new TypeReference<Set<String>>() {
                });
            } else {
                Map<String, Object> configKey = new HashMap<>();
                configKey.put(Constants.ID, Constants.DISCUSSION_REPORT_REASON_CONFIG);
                List<Map<String, Object>> configData = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                        Constants.KEYSPACE_SUNBIRD, Constants.SYSTEM_SETTINGS, configKey, Arrays.asList(Constants.VALUE), null);

                if (CollectionUtils.isEmpty(configData)) {
                    return ProjectUtil.returnErrorMsg(Constants.REPORT_REASON_CONFIG_ERROR_MSG, HttpStatus.NOT_FOUND, response, Constants.FAILED);
                }

                validReasons = new ObjectMapper().readValue(
                        (String) configData.get(0).get(Constants.VALUE), new TypeReference<Set<String>>() {
                        });
                if (validReasons.isEmpty()) validReasons = Collections.emptySet();
                cacheService.putCache(validReasonsKey, validReasons);
            }

            Map<String, Object> propertyMap = new HashMap<>();
            propertyMap.put(Constants.DISCUSSION_ID, discussionId);
            List<Map<String, Object>> reportReasons = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                    Constants.KEYSPACE_SUNBIRD, Constants.DISCUSSION_POST_REPORT_LOOKUP_BY_POST, propertyMap, Arrays.asList(Constants.REASON), null);

            if (CollectionUtils.isEmpty(reportReasons)) {
                return ProjectUtil.returnErrorMsg(Constants.NO_REPORT_REASON_FOUND_ERROR_MSG, HttpStatus.OK, response, Constants.FAILED);
            }

            Map<String, Integer> reasonCountMap = new HashMap<>();
            int totalCount = 0;

            for (Map<String, Object> report : reportReasons) {
                String reasons = (String) report.get(Constants.REASON);
                if (StringUtils.isNotBlank(reasons)) {
                    for (String reason : reasons.split(Constants.COMMA)) {
                        reason = reason.trim();
                        if (validReasons.contains(reason)) {
                            reasonCountMap.put(reason, reasonCountMap.getOrDefault(reason, 0) + 1);
                            totalCount++;
                        }
                    }
                }
            }

            Map<String, Map<String, Object>> statsMap = new HashMap<>();
            for (String reason : validReasons) {
                int count = reasonCountMap.getOrDefault(reason, 0);
                double percentage = totalCount > 0 ? (count * 100.0) / totalCount : 0.0;
                Map<String, Object> reasonStats = new HashMap<>();
                reasonStats.put(Constants.COUNT, count);
                reasonStats.put(Constants.PERCENTAGE, percentage);
                statsMap.put(reason, reasonStats);
            }

            Map<String, Object> result = new HashMap<>();
            result.put(Constants.TOTAL_COUNT, totalCount);
            result.put(Constants.REPORT_REASONS, statsMap);

            cacheService.putCache(redisKey, result);
            response.getResult().putAll(result);
            return response;
        } catch (Exception e) {
            log.error("Failed to get report statistics", e);
            DiscussionServiceUtil.createErrorResponse(response, Constants.GET_REPORT_STATISTICS_ERROR_MSG, HttpStatus.INTERNAL_SERVER_ERROR, Constants.FAILED);
            return response;
        }
    }

    public ApiResponse migrateRecentReportedTime() {
        log.info("DiscussionServiceImpl::migrateRecentReportedTime");
        ApiResponse response = ProjectUtil.createDefaultResponse("api.discussion.migrateRecentReportedTime");
        try {
            Map<String, Object> propertyMap = new HashMap<>();
            List<Map<String, Object>> reportedDiscussionIds = cassandraOperation.getRecordsByPropertiesByKey(
                    Constants.KEYSPACE_SUNBIRD,
                    Constants.DISCUSSION_POST_REPORT_LOOKUP_BY_POST,
                    propertyMap,
                    Arrays.asList(Constants.DISCUSSION_ID, Constants.CREATED_ON_KEY), ""
            );

            Map<String, Date> latestReportedTimeMap = new HashMap<>();

            for (Map<String, Object> record : reportedDiscussionIds) {
                String discussionId = (String) record.get(Constants.DISCUSSION_ID_KEY);
                Timestamp createdOn = Timestamp.from(((Instant) record.get(Constants.CREATED_ON_KEY)));
                if (latestReportedTimeMap.containsKey(discussionId)) {
                    Date existingTime = latestReportedTimeMap.get(discussionId);
                    if (createdOn.after(existingTime)) {
                        latestReportedTimeMap.put(discussionId, createdOn);
                    }
                } else {
                    latestReportedTimeMap.put(discussionId, createdOn);
                }
            }
            log.info("Latest reported times: {}", latestReportedTimeMap);

            for (String discussionId : latestReportedTimeMap.keySet()) {
                Optional<DiscussionEntity> discussionEntityOptional = discussionRepository.findById(discussionId);
                if (discussionEntityOptional.isPresent()) {
                    DiscussionEntity discussionEntity = discussionEntityOptional.get();
                    ObjectNode data = (ObjectNode) discussionEntity.getData();

                    data.put(Constants.RECENT_REPORTED_ON, getFormattedCurrentTime(latestReportedTimeMap.get(discussionId)));
                    discussionEntity.setData(data);
                    discussionRepository.save(discussionEntity);
                    Map<String, Object> map = objectMapper.convertValue(data, Map.class);
                    esUtilService.updateDocument(cbServerProperties.getDiscussionEntity(), discussionId, map, cbServerProperties.getElasticDiscussionJsonPath());
                }
            }
            response.setResponseCode(HttpStatus.OK);
            response.getParams().setStatus(Constants.SUCCESS);
            response.getResult().putAll(latestReportedTimeMap);
        } catch (Exception e) {
            log.error("Failed to migrate recent reported time", e);
            DiscussionServiceUtil.createErrorResponse(response, "migrate data failed", HttpStatus.INTERNAL_SERVER_ERROR, Constants.FAILED);
        }
        return response;
    }

    private String getFormattedCurrentTime(Date currentTime) {
        ZonedDateTime zonedDateTime = currentTime.toInstant().atZone(ZoneId.systemDefault());
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(Constants.TIME_FORMAT);
        return zonedDateTime.format(formatter);
    }

    private void deleteCacheByPrefix(String prefix) {
        String pattern = prefix + "_*";
        Set<String> keys = new HashSet<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match(pattern)
                .count(cbServerProperties.getRedisScanCountSize())
                .build();

        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            cursor.forEachRemaining(keys::add);
        }
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.info("Deleted {} cache keys matching pattern for actions: {}", keys.size(), pattern);
        } else {
            log.info("No cache keys found for pattern: {}", pattern);
        }
    }

}
