package com.igot.cb.discussion.service.impl;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.authentication.util.AccessTokenValidator;
import com.igot.cb.discussion.entity.CommunityEntity;
import com.igot.cb.discussion.entity.DiscussionAnswerPostReplyEntity;
import com.igot.cb.discussion.entity.DiscussionEntity;
import com.igot.cb.discussion.repository.CommunityEngagementRepository;
import com.igot.cb.discussion.repository.DiscussionAnswerPostReplyRepository;
import com.igot.cb.discussion.repository.DiscussionRepository;
import com.igot.cb.discussion.service.DiscussionService;
import com.igot.cb.metrics.service.ApiMetricsTracker;
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
import com.igot.cb.transactional.service.RequestHandlerServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;
import org.springframework.web.multipart.MultipartFile;
import org.sunbird.cloud.storage.BaseStorageService;
import org.sunbird.cloud.storage.factory.StorageConfig;
import org.sunbird.cloud.storage.factory.StorageServiceFactory;
import scala.Option;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static com.igot.cb.pores.util.Constants.*;

@Service
@Slf4j
public class DiscussionServiceImpl implements DiscussionService {
    private BaseStorageService storageService = null;

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
    @Qualifier(Constants.SEARCH_RESULT_REDIS_TEMPLATE)
    private RedisTemplate<String, SearchResult> redisTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private CassandraOperation cassandraOperation;
    @Autowired
    private AccessTokenValidator accessTokenValidator;
    @Autowired
    private CommunityEngagementRepository communityEngagementRepository;
    @Autowired
    private Producer producer;
    @Autowired
    private DiscussionAnswerPostReplyRepository discussionAnswerPostReplyRepository;
    @Autowired
    private NotificationTriggerService notificationTriggerService;
    @Autowired
    private HelperMethodService helperMethodService;

    @Autowired
    private RequestHandlerServiceImpl requestHandlerService;

    @Autowired
    private IProfanityCheckService profanityCheckService;

    @Autowired
    private DiscussionServiceUtil discussionServiceUtil;

    @PostConstruct
    public void init() {
        if (storageService == null) {
            storageService = StorageServiceFactory.getStorageService(new StorageConfig(cbServerProperties.getCloudStorageTypeName(), cbServerProperties.getCloudStorageKey(), cbServerProperties.getCloudStorageSecret().replace("\\n", "\n"), Option.apply(cbServerProperties.getCloudStorageEndpoint()), Option.empty()));
        }
    }

    /**
     * Creates a new discussion based on the provided discussion details.
     *
     * @param discussionDetails The details of the discussion to be created.
     * @return A CustomResponse object containing the result of the operation.
     */
    @Override
    public ApiResponse createDiscussion(JsonNode discussionDetails, String token) {
        log.info("DiscussionService::createDiscussion:creating discussion");
        ApiResponse response = ProjectUtil.createDefaultResponse("discussion.create");
        payloadValidation.validatePayload(Constants.DISCUSSION_VALIDATION_SCHEMA, discussionDetails);
        String userId = accessTokenValidator.verifyUserToken(token);
        if (StringUtils.isBlank(userId) || userId.equals(Constants.UNAUTHORIZED)) {
            response.getParams().setErrMsg(Constants.INVALID_AUTH_TOKEN);
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return response;
        }
        if (!validateCommunityId(discussionDetails.get(Constants.COMMUNITY_ID).asText())) {
            response.getParams().setErrMsg(Constants.INVALID_COMMUNITY_ID);
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return response;
        }
        updateMetricsApiCall(Constants.DISCUSSION_CREATE);
        try {
            JsonNode mentionedUsersNode = discussionDetails.get(MENTIONED_USERS);
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
                ((ObjectNode) discussionDetails).set(MENTIONED_USERS, cleanArray);
                userIdList.addAll(uniqueUserMap.keySet());
            }
            ObjectNode discussionDetailsNode = (ObjectNode) discussionDetails;
            Map<String, Object> propertyMap = new HashMap<>();
            propertyMap.put(Constants.USERID, userId);
            propertyMap.put(Constants.COMMUNITY_ID, discussionDetailsNode.get(Constants.COMMUNITY_ID).asText());
            List<Map<String, Object>> communityDetails = cassandraOperation.getRecordsByPropertiesWithoutFiltering(Constants.KEYSPACE_SUNBIRD, Constants.USER_COMMUNITY, propertyMap, Arrays.asList(Constants.STATUS), null);
            if (communityDetails.isEmpty() || !(boolean) communityDetails.get(0).get(Constants.STATUS)) {
                DiscussionServiceUtil.createErrorResponse(response, Constants.USER_NOT_PART_OF_COMMUNITY, HttpStatus.BAD_REQUEST, Constants.FAILED);
                return response;
            }
            discussionDetailsNode.put(Constants.CREATED_BY, userId);
            discussionDetailsNode.put(Constants.UP_VOTE_COUNT, 0L);
            discussionDetailsNode.put(Constants.STATUS, Constants.ACTIVE);

            DiscussionEntity jsonNodeEntity = new DiscussionEntity();
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());

            UUID id = Uuids.timeBased();
            discussionDetailsNode.put(Constants.DISCUSSION_ID, String.valueOf(id));
            jsonNodeEntity.setDiscussionId(String.valueOf(id));
            jsonNodeEntity.setCreatedOn(currentTime);
            discussionDetailsNode.put(Constants.CREATED_ON, getFormattedCurrentTime(currentTime));
            discussionDetailsNode.put(Constants.UPDATED_ON, getFormattedCurrentTime(currentTime));
            jsonNodeEntity.setUpdatedOn(currentTime);
            jsonNodeEntity.setIsActive(true);
            discussionDetailsNode.put(Constants.IS_ACTIVE, true);
            jsonNodeEntity.setData(discussionDetailsNode);
            jsonNodeEntity.setIsProfane(false);
            long postgresTime = System.currentTimeMillis();
            DiscussionEntity saveJsonEntity = discussionRepository.save(jsonNodeEntity);
            updateMetricsDbOperation(Constants.DISCUSSION_CREATE, Constants.POSTGRES, Constants.INSERT, postgresTime);
            ObjectNode jsonNode = objectMapper.createObjectNode();
            jsonNode.setAll(discussionDetailsNode);
            Map<String, Object> discussionPostDetailsMap = objectMapper.convertValue(discussionDetailsNode, Map.class);
            discussionPostDetailsMap.put(IS_PROFANE,false);
            response.setResponseCode(HttpStatus.CREATED);
            response.getParams().setStatus(Constants.SUCCESS);
            response.setResult(discussionPostDetailsMap);
            esUtilService.addDocument(cbServerProperties.getDiscussionEntity(), saveJsonEntity.getDiscussionId(), discussionPostDetailsMap, cbServerProperties.getElasticDiscussionJsonPath());
            cacheService.putCache(Constants.DISCUSSION_CACHE_PREFIX + saveJsonEntity.getDiscussionId(), jsonNode);
            deleteCacheByCommunity(Constants.DISCUSSION_CACHE_PREFIX + discussionDetails.get(Constants.COMMUNITY_ID).asText());
            deleteCacheByCommunity(Constants.DISCUSSION_POSTS_BY_USER + discussionDetails.get(Constants.COMMUNITY_ID).asText() + Constants.UNDER_SCORE + userId);
            updateCacheForFirstFivePages(discussionDetails.get(Constants.COMMUNITY_ID).asText(), false);
            updateCacheForGlobalFeed(userId);
            log.info("Updated cache for global feed");
            Map<String, String> communityObject = new HashMap<>();
            communityObject.put(Constants.COMMUNITY_ID, discussionDetails.get(Constants.COMMUNITY_ID).asText());
            communityObject.put(Constants.STATUS, Constants.INCREMENT);
            communityObject.put(Constants.TYPE, Constants.POST);
            producer.push(cbServerProperties.getCommunityPostCount(), communityObject);
            Map<String, String> userPostCount = new HashMap<>();
            userPostCount.put(Constants.USERID, userId);
            userPostCount.put(Constants.STATUS, Constants.INCREMENT);
            producer.push(cbServerProperties.getKafkaUserPostCount(), userPostCount);
            try {
                String createdBy = discussionDetailsNode.get(Constants.CREATED_BY).asText();
                if (CollectionUtils.isNotEmpty(userIdList)) {
                    List<String> filteredUserIdList = userIdList.stream()
                            .filter(uniqueId -> !uniqueId.equals(createdBy)).toList();

                    if (CollectionUtils.isNotEmpty(filteredUserIdList)) {
                        Map<String, Object> notificationData = Map.of(
                                Constants.COMMUNITY_ID, discussionDetails.get(Constants.COMMUNITY_ID).asText(),
                                Constants.DISCUSSION_ID, discussionDetails.get(Constants.DISCUSSION_ID).asText()
                        );
                        String firstName = helperMethodService.fetchUserFirstName(userId);
                        notificationTriggerService.triggerNotification(TAGGED_POST, ENGAGEMENT, filteredUserIdList, TITLE, firstName, notificationData);
                    }
                }
            } catch (Exception e) {
                log.error("Error while triggering notification", e);
            }
            producer.push(cbServerProperties.getKafkaProcessDetectLanguageTopic(), discussionDetailsNode);
        } catch (Exception e) {
            log.error("Failed to create discussion: {}", e.getMessage(), e);
            DiscussionServiceUtil.createErrorResponse(response, Constants.FAILED_TO_CREATE_DISCUSSION, HttpStatus.INTERNAL_SERVER_ERROR, Constants.FAILED);
            return response;
        }
        return response;
    }

    private void updateCacheForGlobalFeed(String userId) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            SearchCriteria searchCriteria = objectMapper.readValue(cbServerProperties.getFilterCriteriaForGlobalFeed(), SearchCriteria.class);
            getGlobalFeedUsingUserId(searchCriteria, userId, true);
        } catch (Exception e) {
            log.error("Error occured while updating the cache for globalFeed", e);
            throw new RuntimeException("Error parsing filter criteria JSON", e);
        }
    }

    /**
     * Returns the discussion with the given id.
     *
     * @param discussionId The id of the discussion to retrieve
     * @return A CustomResponse containing the discussion's details
     */
    @Override
    public ApiResponse readDiscussion(String discussionId) {
        log.info("reading discussion details");
        ApiResponse response = ProjectUtil.createDefaultResponse("discussion.read");
        if (StringUtils.isEmpty(discussionId)) {
            log.error("discussion not found");
            DiscussionServiceUtil.createErrorResponse(response, Constants.ID_NOT_FOUND, HttpStatus.BAD_REQUEST, Constants.FAILED);
            return response;
        }
        try {
            updateMetricsApiCall(Constants.DISCUSSION_READ);
            long redisTime = System.currentTimeMillis();
            String cachedJson = cacheService.getCache(Constants.DISCUSSION_CACHE_PREFIX + discussionId);
            updateMetricsDbOperation(Constants.DISCUSSION_READ, Constants.REDIS, Constants.READ, redisTime);
            if (StringUtils.isNotEmpty(cachedJson)) {
                log.info("discussion Record coming from redis cache");
                response.setMessage(Constants.SUCCESS);
                response.setResponseCode(HttpStatus.OK);
                response.setResult((Map<String, Object>) objectMapper.readValue(cachedJson, new TypeReference<Object>() {
                }));
            } else {
                long postgresTime = System.currentTimeMillis();
                Optional<DiscussionEntity> entityOptional = discussionRepository.findById(discussionId);
                updateMetricsDbOperation(Constants.DISCUSSION_READ, Constants.POSTGRES, Constants.READ, postgresTime);
                if (entityOptional.isPresent()) {
                    DiscussionEntity discussionEntity = entityOptional.get();
                    cacheService.putCache(Constants.DISCUSSION_CACHE_PREFIX + discussionId, discussionEntity.getData());
                    log.info("discussion Record coming from postgres db");
                    response.setMessage(Constants.SUCCESS);
                    response.setResponseCode(HttpStatus.OK);
                    response.setResult((Map<String, Object>) objectMapper.convertValue(discussionEntity.getData(), new TypeReference<Object>() {
                    }));
                    response.getResult().put(Constants.IS_ACTIVE, discussionEntity.getIsActive());
                    response.getResult().put(Constants.CREATED_ON, discussionEntity.getCreatedOn());
                } else {
                    log.error("Invalid discussionId: {}", discussionId);
                    DiscussionServiceUtil.createErrorResponse(response, Constants.INVALID_ID, HttpStatus.NOT_FOUND, Constants.FAILED);
                    return response;
                }
            }
        } catch (Exception e) {
            log.error(" JSON for discussionId {}: {}", discussionId, e.getMessage(), e);
            DiscussionServiceUtil.createErrorResponse(response, "Failed to read the discussion", HttpStatus.INTERNAL_SERVER_ERROR, Constants.FAILED);
            return response;
        }
        return response;
    }


    /**
     * Updates the discussion with the given id based on the provided update data.
     *
     * @param updateData The data to be used for the update operation.
     * @return A CustomResponse object containing the result of the operation.
     */
    @Override
    public ApiResponse updateDiscussion(JsonNode updateData, String token) {
        ApiMetricsTracker.enableTracking();
        ApiResponse response = ProjectUtil.createDefaultResponse("update.Discussion");
        payloadValidation.validatePayload(Constants.DISCUSSION_UPDATE_VALIDATION_SCHEMA, updateData);
        String userId = accessTokenValidator.verifyUserToken(token);
        if (StringUtils.isBlank(userId) || Constants.UNAUTHORIZED.equals(userId)) {
            response.getParams().setErrMsg(Constants.INVALID_AUTH_TOKEN);
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return response;
        }
        try {
            updateMetricsApiCall(Constants.DISCUSSION_UPDATE);
            String discussionId = updateData.get(Constants.DISCUSSION_ID).asText();
            long postgresTime = System.currentTimeMillis();
            Optional<DiscussionEntity> discussionEntity = discussionRepository.findById(discussionId);
            updateMetricsDbOperation(Constants.DISCUSSION_UPDATE, Constants.POSTGRES, Constants.READ, postgresTime);
            if (!discussionEntity.isPresent()) {
                DiscussionServiceUtil.createErrorResponse(response, "Discussion not found", HttpStatus.NOT_FOUND, Constants.FAILED);
                return response;
            }
            DiscussionEntity discussionDbData = discussionEntity.get();
            if (!discussionDbData.getIsActive()) {
                DiscussionServiceUtil.createErrorResponse(response, Constants.DISCUSSION_IS_NOT_ACTIVE, HttpStatus.BAD_REQUEST, Constants.FAILED);
                return response;
            }
            ObjectNode data = (ObjectNode) discussionDbData.getData();
            ObjectNode updateDataNode = (ObjectNode) updateData;

            Set<String> existingMentionedUserIds = new HashSet<>();
            data.withArray(MENTIONED_USERS).forEach(userNode -> {
                String userid = userNode.path(USER_ID_RQST).asText(null);
                if (StringUtils.isNotBlank(userid)) existingMentionedUserIds.add(userid);
            });
            Set<String> seenUserIdsInRequest = new HashSet<>();
            List<String> newlyAddedUserIds = new ArrayList<>();
            ArrayNode uniqueMentionedUsers = objectMapper.createArrayNode();
            JsonNode incomingMentionedUsers = updateData.path(MENTIONED_USERS);
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

            updateDataNode.set(MENTIONED_USERS, uniqueMentionedUsers);


            if (data.get(Constants.COMMUNITY_ID) != null && !data.get(Constants.COMMUNITY_ID).asText().equals(updateDataNode.get(Constants.COMMUNITY_ID).asText())) {
                DiscussionServiceUtil.createErrorResponse(response, Constants.COMMUNITY_ID_CANNOT_BE_UPDATED, HttpStatus.BAD_REQUEST, Constants.FAILED);
                return response;
            }
            String communityId = updateData.get(Constants.COMMUNITY_ID).asText();
            updateDataNode.remove(Constants.COMMUNITY_ID);
            updateDataNode.remove(Constants.DISCUSSION_ID);
            data.setAll(updateDataNode);

            if (!updateData.has(Constants.IS_INITIAL_UPLOAD) || !updateData.get(Constants.IS_INITIAL_UPLOAD).asBoolean()) {
                Timestamp currentTime = new Timestamp(System.currentTimeMillis());
                data.put(Constants.UPDATED_ON, getFormattedCurrentTime(currentTime));
                discussionDbData.setUpdatedOn(currentTime);
            }

            discussionDbData.setData(data);
            discussionDbData.setIsProfane(false);
            long postgresInsertTime = System.currentTimeMillis();
            discussionRepository.save(discussionDbData);
            updateMetricsDbOperation(Constants.DISCUSSION_CREATE, Constants.POSTGRES, Constants.UPDATE_KEY, postgresInsertTime);
            ObjectNode jsonNode = objectMapper.createObjectNode();
            jsonNode.setAll(data);

            Map<String, Object> discussionPostDetailsMap = objectMapper.convertValue(jsonNode, Map.class);
            discussionPostDetailsMap.put(IS_PROFANE,false);
            esUtilService.updateDocument(cbServerProperties.getDiscussionEntity(), discussionDbData.getDiscussionId(), discussionPostDetailsMap, cbServerProperties.getElasticDiscussionJsonPath());
            cacheService.putCache(Constants.DISCUSSION_CACHE_PREFIX + discussionDbData.getDiscussionId(), jsonNode);
            deleteCacheByCommunity(Constants.DISCUSSION_POSTS_BY_USER + data.get(Constants.COMMUNITY_ID).asText() + Constants.UNDER_SCORE + userId);
            if (data.has(Constants.CATEGORY_TYPE)
                    && data.get(Constants.CATEGORY_TYPE).isArray()
                    && StreamSupport.stream(data.get(Constants.CATEGORY_TYPE).spliterator(), false)
                    .anyMatch(node -> Constants.DOCUMENT.equals(node.asText()))) {
                deleteCacheByCommunity(Constants.DISCUSSION_DOCUMENT_POST + data.get(Constants.COMMUNITY_ID).asText());
                updateCacheForFirstFivePages(communityId, true);
            }
            Map<String, Object> responseMap = objectMapper.convertValue(discussionDbData, new TypeReference<Map<String, Object>>() {
            });
            if (MapUtils.isNotEmpty(responseMap)) {
                responseMap.remove(IS_PROFANE);
                responseMap.remove(Constants.PROFANITY_RESPONSE);
            }
            response.setResponseCode(HttpStatus.OK);
            response.setResult(responseMap);
            response.getParams().setStatus(Constants.SUCCESS);
            deleteCacheByCommunity(Constants.DISCUSSION_CACHE_PREFIX + communityId);
            updateCacheForFirstFivePages(communityId, false);
            updateCacheForGlobalFeed(userId);
            log.info("Updated cache for global feed");
            try {
                String discussionOwner = discussionDbData.getData().get(Constants.CREATED_BY).asText();
                if (CollectionUtils.isNotEmpty(newlyAddedUserIds)) {
                    List<String> filteredUserIds = newlyAddedUserIds.stream()
                            .filter(id -> !id.equals(discussionOwner))
                            .toList();
                    if (CollectionUtils.isNotEmpty(filteredUserIds)) {
                        Map<String, Object> notificationData = Map.of(
                                Constants.COMMUNITY_ID, discussionDbData.getData().get(Constants.COMMUNITY_ID).asText(),
                                Constants.DISCUSSION_ID, discussionDbData.getDiscussionId()
                        );
                        String firstName = helperMethodService.fetchUserFirstName(userId);
                        notificationTriggerService.triggerNotification(TAGGED_POST, ENGAGEMENT, newlyAddedUserIds, TITLE, firstName, notificationData);
                    }
                }
            } catch (Exception e) {
                log.error("Error while triggering notification", e);
            }
            producer.push(cbServerProperties.getKafkaProcessDetectLanguageTopic(), jsonNode);
        } catch (Exception e) {
            log.error("Failed to update the discussion: ", e);
            DiscussionServiceUtil.createErrorResponse(response, "Failed to update the discussion", HttpStatus.INTERNAL_SERVER_ERROR, Constants.FAILED);
            return response;
        }
        return response;
    }


    @Override
    public ApiResponse searchDiscussion(SearchCriteria searchCriteria, boolean isOverride) {
        log.info("DiscussionServiceImpl::searchDiscussion");
        ApiMetricsTracker.enableTracking();
        ApiResponse response = ProjectUtil.createDefaultResponse("search.discussion");
        boolean isTrending = isTrendingPost(searchCriteria);
        String cacheKey = generateRedisTokenKey(searchCriteria);
        SearchResult searchResult = null;
        if (!isOverride) {
            searchResult = redisTemplate.opsForValue().get(cacheKey);
        }

        if (searchResult != null) {
            log.info("DiscussionServiceImpl::searchDiscussion:  search result fetched from redis");
            response.getResult().put(Constants.SEARCH_RESULTS, searchResult);
            DiscussionServiceUtil.createSuccessResponse(response);
            return response;
        }
        String searchString = searchCriteria.getSearchString();
        if (searchString != null && !searchString.isEmpty() && searchString.length() < 3) {
            DiscussionServiceUtil.createErrorResponse(response, Constants.MINIMUM_CHARACTERS_NEEDED, HttpStatus.BAD_REQUEST, Constants.FAILED_CONST);
            return response;
        }
        try {
            log.info("DiscussionServiceImpl::searchDiscussion:  search result fetched from es");
            if (MapUtils.isEmpty(searchCriteria.getFilterCriteriaMap())) {
                searchCriteria.setFilterCriteriaMap(new HashMap<>());
            }
            searchCriteria.getFilterCriteriaMap().put(Constants.IS_ACTIVE, true);
            searchCriteria.getFilterCriteriaMap().put(IS_PROFANE, false);

            if (!searchCriteria.getFilterCriteriaMap().containsKey(Constants.STATUS) ||
                    (!searchCriteria.getFilterCriteriaMap().get(Constants.STATUS).equals(Collections.singletonList(Constants.REPORTED)) &&
                            !searchCriteria.getFilterCriteriaMap().get(Constants.STATUS).equals(Collections.singletonList(Constants.SUSPENDED)))) {
                searchCriteria.getFilterCriteriaMap().put(Constants.STATUS, Arrays.asList(Constants.ACTIVE, Constants.REPORTED));
            }

            if (isTrending) {
                List<String> communityIds = getTrendingPosts();
                searchCriteria.getFilterCriteriaMap().put(Constants.COMMUNITY_ID, communityIds);
            }
            searchResult = esUtilService.searchDocuments(cbServerProperties.getDiscussionEntity(), searchCriteria, cbServerProperties.getElasticDiscussionJsonPath());
            if (CollectionUtils.isEmpty(searchResult.getData())) {
                DiscussionServiceUtil.createErrorResponse(response, Constants.NO_DATA_FOUND, HttpStatus.OK, Constants.SUCCESS);
                response.getResult().put(Constants.SEARCH_RESULTS, searchResult);
                return response;
            }
            List<Map<String, Object>> discussions = searchResult.getData();

            if (searchCriteria.getRequestedFields().contains(Constants.CREATED_BY) || searchCriteria.getRequestedFields().isEmpty()) {
                boolean isAnswerPost = false;
                if (searchCriteria.getFilterCriteriaMap().containsKey(Constants.TYPE) && Constants.ANSWER_POST.equals(searchCriteria.getFilterCriteriaMap().get(Constants.TYPE))) {
                    isAnswerPost = true;
                }
                fetchAndEnhanceDiscussions(discussions, isAnswerPost);
            }
            if (isTrending) {
                enhanceCommunityData(discussions);
            }
            searchResult.setData(discussions);
            redisTemplate.opsForValue().set(cacheKey, searchResult, cbServerProperties.getSearchResultRedisTtl(), TimeUnit.SECONDS);
            response.getResult().put(Constants.SEARCH_RESULTS, searchResult);
            DiscussionServiceUtil.createSuccessResponse(response);
            return response;
        } catch (Exception e) {
            log.error("error while searching discussion : {} .", e.getMessage(), e);
            DiscussionServiceUtil.createErrorResponse(response, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR, Constants.FAILED_CONST);
            return response;
        }
    }

    /**
     * Deletes the discussion with the given id.
     *
     * @param discussionId The id of the discussion to be deleted.
     * @return A CustomResponse object containing the result of the operation.
     */
    @Override
    public ApiResponse deleteDiscussion(String discussionId, String type, String token) {
        log.info("DiscussionServiceImpl::delete Discussion");
        ApiResponse response = ProjectUtil.createDefaultResponse("delete.discussion");
        try {
            String userId = accessTokenValidator.verifyUserToken(token);
            if (StringUtils.isBlank(userId)) {
                response.getParams().setErrMsg(Constants.INVALID_AUTH_TOKEN);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                return response;
            }

            if (StringUtils.isNotEmpty(discussionId)) {
                Optional<DiscussionEntity> entityOptional = discussionRepository.findById(discussionId);
                if (entityOptional.isPresent()) {
                    DiscussionEntity jasonEntity = entityOptional.get();
                    JsonNode data = jasonEntity.getData();
                    if (!type.equals(data.get(Constants.TYPE).asText())) {
                        DiscussionServiceUtil.createErrorResponse(response, Constants.INVALID_TYPE + type, HttpStatus.BAD_REQUEST, Constants.FAILED);
                        return response;
                    }
                    Timestamp currentTime = new Timestamp(System.currentTimeMillis());
                    if (jasonEntity.getIsActive()) {
                        jasonEntity.setIsActive(false);
                        jasonEntity.setUpdatedOn(currentTime);
                        ((ObjectNode) data).put(Constants.IS_ACTIVE, false);
                        ((ObjectNode) data).put(Constants.UPDATED_ON, String.valueOf(currentTime));
                        jasonEntity.setData(data);
                        jasonEntity.setDiscussionId(discussionId);
                        jasonEntity.setUpdatedOn(currentTime);
                        discussionRepository.save(jasonEntity);
                        Map<String, Object> map = objectMapper.convertValue(data, Map.class);
                        map.put(Constants.IS_ACTIVE, false);
                        esUtilService.updateDocument(cbServerProperties.getDiscussionEntity(), discussionId, map, cbServerProperties.getElasticDiscussionJsonPath());
                        cacheService.putCache(Constants.DISCUSSION_CACHE_PREFIX + discussionId, data);
                        log.info("Discussion details deleted successfully");
                        response.setResponseCode(HttpStatus.OK);
                        response.setMessage(Constants.DELETED_SUCCESSFULLY);
                        response.getParams().setStatus(Constants.SUCCESS);
                        Map<String, String> communityObject = new HashMap<>();
                        communityObject.put(Constants.COMMUNITY_ID,
                                (String) map.get(Constants.COMMUNITY_ID));
                        communityObject.put(Constants.STATUS, Constants.DECREMENT);
                        if (Constants.QUESTION.equalsIgnoreCase(data.get(Constants.TYPE).asText())) {
                            communityObject.put(Constants.TYPE, Constants.POST);
                        } else {
                            communityObject.put(Constants.TYPE, Constants.ANSWER_POST);
                            DiscussionEntity discussionEntity = discussionRepository.findById(
                                    data.get(Constants.PARENT_DISCUSSION_ID).asText()).orElse(null);
                            if (discussionEntity != null) {
                                updateAnswerPostToDiscussion(discussionEntity, discussionId,
                                        Constants.DECREMENT);
                            }
                            redisTemplate.opsForValue().getAndDelete(
                                    discussionServiceUtil.generateRedisJwtTokenKey(createSearchCriteriaWithDefaults(
                                            data.get(Constants.PARENT_DISCUSSION_ID).asText(),
                                            data.get(Constants.COMMUNITY_ID).asText(),
                                            Constants.ANSWER_POST)));
                        }
                        deleteCacheByCommunity(Constants.DISCUSSION_CACHE_PREFIX + map.get(Constants.COMMUNITY_ID));
                        updateCacheForFirstFivePages((String) map.get(Constants.COMMUNITY_ID), false);
                        updateCacheForGlobalFeed(userId);
                        log.info("Updated cache for global feed");
                        producer.push(cbServerProperties.getCommunityPostCount(), communityObject);
                        if (Constants.QUESTION.equalsIgnoreCase(data.get(Constants.TYPE).asText())) {
                            Map<String, String> userPostCount = new HashMap<>();
                            userPostCount.put(Constants.USERID, userId);
                            userPostCount.put(Constants.STATUS, Constants.DECREMENT);
                            producer.push(cbServerProperties.getKafkaUserPostCount(), userPostCount);
                        }
                        return response;
                    } else {
                        log.info("Discussion is already inactive.");
                        DiscussionServiceUtil.createErrorResponse(response, Constants.DISCUSSION_IS_INACTIVE, HttpStatus.ALREADY_REPORTED, Constants.SUCCESS);
                        return response;
                    }
                } else {
                    DiscussionServiceUtil.createErrorResponse(response, Constants.INVALID_ID, HttpStatus.BAD_REQUEST, Constants.NO_DATA_FOUND);
                    return response;
                }
            }
        } catch (Exception e) {
            log.error("Error while deleting discussion with ID: {}. Exception: {}", discussionId, e.getMessage(), e);
            DiscussionServiceUtil.createErrorResponse(response, Constants.FAILED_TO_DELETE_DISCUSSION, HttpStatus.INTERNAL_SERVER_ERROR, Constants.FAILED);
            return response;
        }
        return response;
    }

    private ApiResponse vote(String discussionId, String type, String token, String voteType) {
        log.info("DiscussionServiceImpl::vote - Type: {}", voteType);
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.DISCUSSION_VOTE_API);
        try {
            String userId = accessTokenValidator.verifyUserToken(token);
            if (StringUtils.isEmpty(userId) || Constants.UNAUTHORIZED.equals(userId)) {
                DiscussionServiceUtil.createErrorResponse(response, Constants.INVALID_AUTH_TOKEN, HttpStatus.BAD_REQUEST, Constants.FAILED);
                return response;
            }

            boolean isAnswerReply = Constants.ANSWER_POST_REPLY.equals(type);
            Object entityObject = isAnswerReply
                    ? discussionAnswerPostReplyRepository.findById(discussionId).orElse(null)
                    : discussionRepository.findById(discussionId).orElse(null);

            if (entityObject == null) {
                DiscussionServiceUtil.createErrorResponse(response, Constants.DISCUSSION_NOT_FOUND, HttpStatus.BAD_REQUEST, Constants.FAILED);
                return response;
            }

            JsonNode dataNode;
            Boolean isActive;
            if (isAnswerReply) {
                DiscussionAnswerPostReplyEntity replyEntity = (DiscussionAnswerPostReplyEntity) entityObject;
                dataNode = replyEntity.getData();
                ((ObjectNode) dataNode).put(IS_PROFANE, replyEntity.getIsProfane());
                log.info("Profanity status for the reply: {} for discussionId : {} ", replyEntity.getIsProfane(), replyEntity.getDiscussionId());
                isActive = replyEntity.getIsActive();
            } else {
                DiscussionEntity discussionEntity = (DiscussionEntity) entityObject;
                dataNode = discussionEntity.getData();
                ((ObjectNode) dataNode).put(IS_PROFANE, discussionEntity.getIsProfane());
                log.info("Profanity status for the post: {} for discussionId : {}", discussionEntity.getIsProfane(), discussionEntity.getDiscussionId());
                isActive = discussionEntity.getIsActive();
            }

            HashMap<String, Object> discussionData = objectMapper.convertValue(dataNode, HashMap.class);

            if (!isActive) {
                DiscussionServiceUtil.createErrorResponse(response, Constants.DISCUSSION_IS_INACTIVE, HttpStatus.BAD_REQUEST, Constants.FAILED);
                return response;
            }
            if (!type.equals(discussionData.get(Constants.TYPE))) {
                DiscussionServiceUtil.createErrorResponse(response, Constants.INVALID_TYPE + type, HttpStatus.BAD_REQUEST, Constants.FAILED);
                return response;
            }

            boolean currentVote = Constants.UP.equals(voteType);

            Object upVoteCountObj = discussionData.get(Constants.UP_VOTE_COUNT);
            long existingUpVoteCount = (upVoteCountObj instanceof Number) ? ((Number) upVoteCountObj).longValue() : 0L;

            Map<String, Object> properties = new HashMap<>();
            properties.put(Constants.DISCUSSION_ID_KEY, discussionId);
            properties.put(Constants.USERID, userId);
            List<Map<String, Object>> existingResponseList = cassandraOperation.getRecordsByPropertiesWithoutFiltering(Constants.KEYSPACE_SUNBIRD, Constants.USER_POST_VOTES, properties, null, null);

            if (CollectionUtils.isEmpty(existingResponseList)) {
                if (currentVote) {
                    Map<String, Object> propertyMap = new HashMap<>();
                    propertyMap.put(Constants.USER_ID_RQST, userId);
                    propertyMap.put(Constants.DISCUSSION_ID_KEY, discussionId);
                    propertyMap.put(Constants.VOTE_TYPE, currentVote);

                    ApiResponse result = (ApiResponse) cassandraOperation.insertRecord(Constants.KEYSPACE_SUNBIRD, Constants.USER_POST_VOTES, propertyMap);
                    Map<String, Object> resultMap = result.getResult();
                    if (!resultMap.get(Constants.RESPONSE).equals(Constants.SUCCESS)) {
                        response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
                        response.setMessage(Constants.FAILED);
                        return response;
                    }
                    Map<String, String> communityObject = new HashMap<>();
                    communityObject.put(Constants.COMMUNITY_ID, dataNode.get(Constants.COMMUNITY_ID).asText());
                    communityObject.put(Constants.STATUS, Constants.INCREMENT);
                    communityObject.put(Constants.DISCUSSION_ID, discussionId);
                    producer.push(cbServerProperties.getCommunityLikeCount(), communityObject);
                } else {
                    DiscussionServiceUtil.createErrorResponse(response, Constants.USER_MUST_VOTE_FIRST, HttpStatus.BAD_REQUEST, Constants.FAILED);
                    return response;
                }
            } else {
                Map<String, Object> userVoteData = existingResponseList.get(0);
                if (userVoteData.get(Constants.VOTE_TYPE).equals(currentVote)) {
                    DiscussionServiceUtil.createErrorResponse(response, String.format(Constants.USER_ALREADY_VOTED, voteType), HttpStatus.ALREADY_REPORTED, Constants.FAILED);
                    return response;
                }

                Map<String, Object> updateAttribute = new HashMap<>();
                updateAttribute.put(Constants.VOTE_TYPE, currentVote);
                Map<String, Object> compositeKeys = new HashMap<>();
                compositeKeys.put(Constants.USER_ID_RQST, userId);
                compositeKeys.put(Constants.DISCUSSION_ID_KEY, discussionId);

                Map<String, Object> result = cassandraOperation.updateRecordByCompositeKey(Constants.KEYSPACE_SUNBIRD, Constants.USER_POST_VOTES, updateAttribute, compositeKeys);
                if (!result.get(Constants.RESPONSE).equals(Constants.SUCCESS)) {
                    DiscussionServiceUtil.createErrorResponse(response, Constants.FAILED_TO_VOTE, HttpStatus.INTERNAL_SERVER_ERROR, Constants.FAILED);
                    return response;
                }

                Map<String, String> communityObject = new HashMap<>();
                communityObject.put(Constants.COMMUNITY_ID, dataNode.get(Constants.COMMUNITY_ID).asText());
                communityObject.put(Constants.DISCUSSION_ID, discussionId);
                communityObject.put(Constants.STATUS, Constants.UP.equals(voteType) ? Constants.INCREMENT : Constants.DECREMENT);
                producer.push(cbServerProperties.getCommunityLikeCount(), communityObject);
            }

            discussionData.put(Constants.UP_VOTE_COUNT, currentVote ? existingUpVoteCount + 1 : existingUpVoteCount - 1);

            JsonNode updatedData = objectMapper.valueToTree(discussionData);
            if (isAnswerReply) {
                DiscussionAnswerPostReplyEntity replyEntity = (DiscussionAnswerPostReplyEntity) entityObject;
                replyEntity.setData(updatedData);
                discussionAnswerPostReplyRepository.save(replyEntity);
            } else {
                DiscussionEntity discussionEntity = (DiscussionEntity) entityObject;
                discussionEntity.setData(updatedData);
                discussionRepository.save(discussionEntity);
            }

            esUtilService.updateDocument(cbServerProperties.getDiscussionEntity(), discussionId, discussionData, cbServerProperties.getElasticDiscussionJsonPath());
            cacheService.putCache(Constants.DISCUSSION_CACHE_PREFIX + discussionId, discussionData);

            if (!isAnswerReply) {
                updateCacheForFirstFivePages(dataNode.get(Constants.COMMUNITY_ID).asText(), false);
                updateCacheForGlobalFeed(userId);
            }

            try {
                String createdBy = dataNode.get(Constants.CREATED_BY).asText();

                Map<String, Object> data = Map.of(
                        Constants.COMMUNITY_ID, dataNode.get(Constants.COMMUNITY_ID).asText(),
                        Constants.DISCUSSION_ID, type.equalsIgnoreCase(Constants.QUESTION) ? discussionId : discussionData.get(Constants.PARENT_DISCUSSION_ID)
                );


                String firstName = helperMethodService.fetchUserFirstName(userId);
                log.info("Notification trigger started");
                if (currentVote && !userId.equals(createdBy)) {
                    if (type.equalsIgnoreCase(Constants.QUESTION)) {
                        notificationTriggerService.triggerNotification(LIKED_POST, ENGAGEMENT, List.of(createdBy), TITLE, firstName, data);
                    } else if (type.equalsIgnoreCase(Constants.ANSWER_POST)) {
                        notificationTriggerService.triggerNotification(POST_COMMENT, ENGAGEMENT, List.of(createdBy), TITLE, firstName, data);
                    } else if (type.equalsIgnoreCase(Constants.ANSWER_POST_REPLY)) {
                        notificationTriggerService.triggerNotification(REPLIED_POST, ENGAGEMENT, List.of(createdBy), TITLE, firstName, data);
                    }
                }
            } catch (Exception e) {
                log.error("Error while triggering notification", e);
            }

            if (Constants.ANSWER_POST.equals(type)) {
                redisTemplate.opsForValue()
                        .getAndDelete(discussionServiceUtil.generateRedisJwtTokenKey(createSearchCriteriaWithDefaults(
                                (String) discussionData.get(Constants.PARENT_DISCUSSION_ID),
                                (String) discussionData.get(Constants.COMMUNITY_ID),
                                Constants.ANSWER_POST)));
            }
            if (Constants.ANSWER_POST_REPLY.equals(type)) {
                redisTemplate.opsForValue().getAndDelete(discussionServiceUtil.generateRedisJwtTokenKey(createDefaultSearchCriteria(
                        (String) discussionData.get(Constants.PARENT_ANSWER_POST_ID),
                        (String) discussionData.get(Constants.COMMUNITY_ID))));
            }
            response.setResponseCode(HttpStatus.OK);
            response.getParams().setStatus(Constants.SUCCESS);
        } catch (Exception e) {
            log.error("Error while processing vote: {}", e.getMessage(), e);
            response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
            response.setMessage(Constants.FAILED);
            response.getParams().setErrMsg(Constants.FAILED);
            return response;
        }
        return response;
    }

    public List<Object> fetchDataForKeys(List<String> keys, boolean isUserData) {
        // Fetch values for all keys from Redis
        List<Object> values;
        if (isUserData) {
            values = cacheService.hget(keys);
        } else {
            values = cacheService.hgetMulti(keys);
        }

        // Create a map of key-value pairs, converting stringified JSON objects to User objects
        return keys.stream()
                .filter(key -> values.get(keys.indexOf(key)) != null) // Filter out null values
                .map(key -> {
                    String stringifiedJson = (String) values.get(keys.indexOf(key)); // Cast the value to String
                    try {
                        // Convert the stringified JSON to a User object using ObjectMapper
                        return objectMapper.readValue(stringifiedJson, Object.class); // You can map this to a specific User type if needed
                    } catch (Exception e) {
                        log.error("Failed to fetch user data from redis ", e.getMessage(), e);
                        return null; // Return null in case of error
                    }
                })
                .collect(Collectors.toList());
    }

    public List<Object> fetchUserFromPrimary(List<String> userIds) {
        log.info("DiscussionServiceImpl::fetchUserFromPrimary: Fetching user data from Cassandra");
        List<Object> userList = new ArrayList<>();
        Map<String, Object> propertyMap = new HashMap<>();
        propertyMap.put(Constants.ID, userIds);
        long startTime = System.currentTimeMillis();
        List<Map<String, Object>> userInfoList = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                Constants.KEYSPACE_SUNBIRD, Constants.USER_TABLE, propertyMap,
                Arrays.asList(Constants.PROFILE_DETAILS, Constants.FIRST_NAME, Constants.ID), null);
        updateMetricsDbOperation(Constants.DISCUSSION_SEARCH, Constants.CASSANDRA, Constants.READ, startTime);
        userList = userInfoList.stream()
                .map(userInfo -> {
                    Map<String, Object> userMap = new HashMap<>();

                    // Extract user ID and user name
                    String userId = (String) userInfo.get(Constants.ID);
                    String userName = (String) userInfo.get(Constants.FIRST_NAME);

                    userMap.put(Constants.USER_ID_KEY, userId);
                    userMap.put(Constants.FIRST_NAME_KEY, userName);

                    // Process profile details if present
                    String profileDetails = (String) userInfo.get(Constants.PROFILE_DETAILS);
                    if (StringUtils.isNotBlank(profileDetails)) {
                        try {
                            // Convert JSON profile details to a Map
                            Map<String, Object> profileDetailsMap = objectMapper.readValue(profileDetails,
                                    new TypeReference<HashMap<String, Object>>() {
                                    });

                            // Check for profile image and add to userMap if available
                            if (MapUtils.isNotEmpty(profileDetailsMap)) {
                                if (profileDetailsMap.containsKey(Constants.PROFILE_IMG) && StringUtils.isNotBlank((String) profileDetailsMap.get(Constants.PROFILE_IMG))) {
                                    userMap.put(Constants.PROFILE_IMG_KEY, (String) profileDetailsMap.get(Constants.PROFILE_IMG));
                                }
                                if (profileDetailsMap.containsKey(Constants.DESIGNATION_KEY) && StringUtils.isNotEmpty((String) profileDetailsMap.get(Constants.DESIGNATION_KEY))) {

                                    userMap.put(Constants.DESIGNATION_KEY, (String) profileDetailsMap.get(Constants.PROFILE_IMG));
                                }
                                if (profileDetailsMap.containsKey(Constants.EMPLOYMENT_DETAILS) && MapUtils.isNotEmpty(
                                        (Map<?, ?>) profileDetailsMap.get(Constants.EMPLOYMENT_DETAILS)) && ((Map<?, ?>) profileDetailsMap.get(Constants.EMPLOYMENT_DETAILS)).containsKey(Constants.DEPARTMENT_KEY) && StringUtils.isNotBlank(
                                        (String) ((Map<?, ?>) profileDetailsMap.get(Constants.EMPLOYMENT_DETAILS)).get(Constants.DEPARTMENT_KEY))) {
                                    userMap.put(Constants.DEPARTMENT, (String) ((Map<?, ?>) profileDetailsMap.get(Constants.EMPLOYMENT_DETAILS)).get(Constants.DEPARTMENT_KEY));

                                }
                            }
                        } catch (JsonProcessingException e) {
                            log.error("Error occurred while converting json object to json string", e);
                        }
                    }

                    return userMap;
                })
                .collect(Collectors.toList());
        return userList;
    }

    @Override
    public ApiResponse createAnswerPost(JsonNode answerPostData, String token) {
        log.info("DiscussionService::createAnswerPost:creating answerPost");
        ApiResponse response = ProjectUtil.createDefaultResponse("discussion.createAnswerPost");
        payloadValidation.validatePayload(Constants.DISCUSSION_ANSWER_POST_VALIDATION_SCHEMA, answerPostData);
        String userId = accessTokenValidator.verifyUserToken(token);
        if (StringUtils.isBlank(userId) || userId.equals(Constants.UNAUTHORIZED)) {
            response.getParams().setErrMsg(Constants.INVALID_AUTH_TOKEN);
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return response;
        }
        updateMetricsApiCall(Constants.DISCUSSION_ANSWER_POST);
        long postgresTime = System.currentTimeMillis();
        DiscussionEntity discussionEntity = discussionRepository.findById(answerPostData.get(Constants.PARENT_DISCUSSION_ID).asText()).orElse(null);
        updateMetricsDbOperation(Constants.DISCUSSION_ANSWER_POST, Constants.POSTGRES, Constants.READ, postgresTime);
        if (discussionEntity == null || !discussionEntity.getIsActive()) {
            return ProjectUtil.returnErrorMsg(Constants.INVALID_PARENT_DISCUSSION_ID, HttpStatus.BAD_REQUEST, response, Constants.FAILED);
        }
        JsonNode data = discussionEntity.getData();
        String type = data.get(Constants.TYPE).asText();
        if (type.equals(Constants.ANSWER_POST)) {
            return ProjectUtil.returnErrorMsg(Constants.PARENT_ANSWER_POST_ID_ERROR, HttpStatus.BAD_REQUEST, response, Constants.FAILED);
        }
        if (data.get(Constants.STATUS).asText().equals(Constants.SUSPENDED)) {
            return ProjectUtil.returnErrorMsg(Constants.PARENT_DISCUSSION_ID_ERROR, HttpStatus.BAD_REQUEST, response, Constants.FAILED);
        }
        if (!answerPostData.get(Constants.COMMUNITY_ID).asText().equals(data.get(Constants.COMMUNITY_ID).asText())) {
            response.getParams().setErrMsg(Constants.INVALID_COMMUNITY_ID);
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return response;
        }

        try {
            JsonNode mentionedUsersNode = answerPostData.get(MENTIONED_USERS);
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
                ((ObjectNode) answerPostData).set(MENTIONED_USERS, cleanArray);
                userIdList.addAll(uniqueUserMap.keySet());
            }
            ObjectNode answerPostDataNode = (ObjectNode) answerPostData;
            Map<String, Object> propertyMap = new HashMap<>();
            propertyMap.put(Constants.USERID, userId);
            propertyMap.put(Constants.COMMUNITY_ID, answerPostDataNode.get(Constants.COMMUNITY_ID).asText());
            List<Map<String, Object>> communityDetails = cassandraOperation.getRecordsByPropertiesWithoutFiltering(Constants.KEYSPACE_SUNBIRD, Constants.USER_COMMUNITY, propertyMap, Arrays.asList(Constants.STATUS), null);
            if (communityDetails.isEmpty() || !(boolean) communityDetails.get(0).get(Constants.STATUS)) {
                DiscussionServiceUtil.createErrorResponse(response, Constants.USER_NOT_PART_OF_COMMUNITY, HttpStatus.BAD_REQUEST, Constants.FAILED);
                return response;
            }
            answerPostDataNode.put(Constants.CREATED_BY, userId);
            answerPostDataNode.put(Constants.VOTE_COUNT, 0);
            answerPostDataNode.put(Constants.STATUS, Constants.ACTIVE);
            answerPostDataNode.put(Constants.PARENT_DISCUSSION_ID, answerPostData.get(Constants.PARENT_DISCUSSION_ID));

            DiscussionEntity jsonNodeEntity = new DiscussionEntity();

            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            UUID id = Uuids.timeBased();
            answerPostDataNode.put(Constants.DISCUSSION_ID, String.valueOf(id));
            jsonNodeEntity.setDiscussionId(String.valueOf(id));
            jsonNodeEntity.setCreatedOn(currentTime);
            answerPostDataNode.put(Constants.CREATED_ON, getFormattedCurrentTime(currentTime));
            answerPostDataNode.put(Constants.UPDATED_ON, getFormattedCurrentTime(currentTime));
            jsonNodeEntity.setIsActive(true);
            answerPostDataNode.put(Constants.IS_ACTIVE, true);
            jsonNodeEntity.setData(answerPostDataNode);
            jsonNodeEntity.setCreatedOn(currentTime);
            jsonNodeEntity.setUpdatedOn(currentTime);
            jsonNodeEntity.setIsProfane(false);
            long timer = System.currentTimeMillis();
            discussionRepository.save(jsonNodeEntity);
            updateMetricsDbOperation(Constants.DISCUSSION_ANSWER_POST, Constants.POSTGRES, Constants.INSERT, timer);

            ObjectNode jsonNode = objectMapper.createObjectNode();
            jsonNode.setAll(answerPostDataNode);
            Map<String, Object> discussionAnswerPostDetailsMap = objectMapper.convertValue(jsonNode, Map.class);
            discussionAnswerPostDetailsMap.put(IS_PROFANE,false);
            esUtilService.addDocument(cbServerProperties.getDiscussionEntity(), String.valueOf(id), discussionAnswerPostDetailsMap, cbServerProperties.getElasticDiscussionJsonPath());
            cacheService.putCache(Constants.DISCUSSION_CACHE_PREFIX + String.valueOf(id), jsonNode);

            updateAnswerPostToDiscussion(discussionEntity, String.valueOf(id), Constants.INCREMENT);
            deleteCacheByCommunity(Constants.DISCUSSION_CACHE_PREFIX + answerPostData.get(Constants.COMMUNITY_ID).asText());
            updateCacheForFirstFivePages(answerPostData.get(Constants.COMMUNITY_ID).asText(), false);
            redisTemplate.opsForValue()
                    .getAndDelete(discussionServiceUtil.generateRedisJwtTokenKey(createSearchCriteriaWithDefaults(
                            answerPostData.get(Constants.PARENT_DISCUSSION_ID).asText(),
                            answerPostData.get(Constants.COMMUNITY_ID).asText(),
                            Constants.ANSWER_POST)));
            // update global feed cache
            updateCacheForGlobalFeed(userId);
            Map<String, String> communityObject = new HashMap<>();
            communityObject.put(Constants.COMMUNITY_ID, answerPostData.get(Constants.COMMUNITY_ID).asText());
            communityObject.put(Constants.STATUS, Constants.INCREMENT);
            communityObject.put(Constants.TYPE, Constants.ANSWER_POST);
            producer.push(cbServerProperties.getCommunityPostCount(), communityObject);

            log.info("AnswerPost created successfully");

            try {
                Map<String, Object> notificationData = Map.of(
                        Constants.COMMUNITY_ID, answerPostData.get(Constants.COMMUNITY_ID).asText(),
                        Constants.DISCUSSION_ID, answerPostData.get(Constants.PARENT_DISCUSSION_ID).asText()
                );
                String discussionOwner = discussionEntity.getData().get(Constants.CREATED_BY).asText();
                String createdBy = answerPostData.get(CREATED_BY).asText();
                String firstName = helperMethodService.fetchUserFirstName(createdBy);
                log.info("Notification trigger started for create answerPost");
                if (mentionedUsersNode == null || mentionedUsersNode.isEmpty()) {
                    if (!userId.equals(discussionOwner)) {
                        notificationTriggerService.triggerNotification(LIKED_COMMENT, ENGAGEMENT, List.of(discussionOwner), TITLE, firstName, notificationData);
                    }
                }
                else if (CollectionUtils.isNotEmpty(userIdList)) {
                    List<String> filteredUserIdList = userIdList.stream()
                            .filter(uniqueId -> !uniqueId.equals(userId))
                            .toList();

                    if (CollectionUtils.isNotEmpty(filteredUserIdList)) {
                        Map<String, Object> answerPostNotificationData = Map.of(
                                Constants.COMMUNITY_ID, answerPostData.get(Constants.COMMUNITY_ID).asText(),
                                Constants.DISCUSSION_ID, answerPostData.get(Constants.PARENT_DISCUSSION_ID).asText()
                        );
                        notificationTriggerService.triggerNotification(TAGGED_COMMENT, ENGAGEMENT, filteredUserIdList, TITLE, firstName, answerPostNotificationData);
                    }
                }
            } catch (Exception e) {
                log.error("Error while triggering notification", e);
            }
            discussionAnswerPostDetailsMap.put(Constants.CREATED_ON, currentTime);
            response.setResponseCode(HttpStatus.CREATED);
            response.getParams().setStatus(Constants.SUCCESS);
            response.setResult(discussionAnswerPostDetailsMap);
            producer.push(cbServerProperties.getKafkaProcessDetectLanguageTopic(), answerPostDataNode);
        } catch (Exception e) {
            log.error("Failed to create AnswerPost: {}", e.getMessage(), e);
            DiscussionServiceUtil.createErrorResponse(response, Constants.FAILED_TO_CREATE_ANSWER_POST, HttpStatus.INTERNAL_SERVER_ERROR, Constants.FAILED);
            return response;
        }
        return response;
    }

    public SearchCriteria createSearchCriteriaWithDefaults(String parentDiscussionId,
                                                            String communityId,
                                                            String type) {
        SearchCriteria criteria = new SearchCriteria();

        // Initialize filterCriteriaMap with default and specified values
        HashMap<String, Object> filterMap = new HashMap<>();
        filterMap.put(Constants.COMMUNITY_ID, communityId);
        filterMap.put(Constants.TYPE, type);
        filterMap.put(Constants.PARENT_DISCUSSION_ID, parentDiscussionId);
        criteria.setFilterCriteriaMap(filterMap);
        // Initialize requestedFields with an empty list
        criteria.setRequestedFields(Collections.emptyList());

        // Set default pagination values
        criteria.setPageNumber(0);
        criteria.setPageSize(10);

        // Set default ordering
        criteria.setOrderBy(Constants.CREATED_ON);
        criteria.setOrderDirection(Constants.DESC);

        // Initialize facets with an empty list
        criteria.setFacets(Collections.emptyList());
        return criteria;

    }

    public void updateAnswerPostToDiscussion(DiscussionEntity discussionEntity, String discussionId, String action) {
        log.info("DiscussionService::updateAnswerPostToDiscussion:inside");
        JsonNode data = discussionEntity.getData();
        Set<String> answerPostSet = new HashSet<>();

        if (data.has(Constants.ANSWER_POSTS)) {
            ArrayNode existingAnswerPosts = (ArrayNode) data.get(Constants.ANSWER_POSTS);
            existingAnswerPosts.forEach(post -> answerPostSet.add(post.asText()));
        }
        if (Constants.INCREMENT.equals(action)) {
            answerPostSet.add(discussionId);
        } else {
            answerPostSet.remove(discussionId);
        }
        ArrayNode arrayNode = objectMapper.valueToTree(answerPostSet);
        ((ObjectNode) data).put(Constants.ANSWER_POSTS, arrayNode);
        ((ObjectNode) data).put(Constants.ANSWER_POST_COUNT, answerPostSet.size());

        discussionEntity.setData(data);
        DiscussionEntity savedEntity = discussionRepository.save(discussionEntity);
        log.info("DiscussionService::updateAnswerPostToDiscussion: Discussion entity updated successfully");

        ObjectNode jsonNode = objectMapper.createObjectNode();
        jsonNode.setAll((ObjectNode) savedEntity.getData());
        Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
        map.put(IS_PROFANE, Boolean.TRUE.equals(discussionEntity.getIsProfane()));
        esUtilService.updateDocument(cbServerProperties.getDiscussionEntity(), discussionEntity.getDiscussionId(), map, cbServerProperties.getElasticDiscussionJsonPath());
        cacheService.putCache(Constants.DISCUSSION_CACHE_PREFIX + discussionEntity.getDiscussionId(), jsonNode);
    }

    @Override
    public ApiResponse upVote(String discussionId, String type, String token) {
        return vote(discussionId, type, token, Constants.UP);
    }

    @Override
    public ApiResponse downVote(String discussionId, String type, String token) {
        return vote(discussionId, type, token, Constants.DOWN);
    }

    @Override
    public ApiResponse report(String token, Map<String, Object> reportData) {
        log.info("DiscussionService::report: Reporting discussion");
        ApiResponse response = ProjectUtil.createDefaultResponse("discussion.report");
        String errorMsg = validateReportPayload(reportData);
        if (StringUtils.isNotEmpty(errorMsg)) {
            return ProjectUtil.returnErrorMsg(errorMsg, HttpStatus.BAD_REQUEST, response, Constants.FAILED);
        }

        String userId = accessTokenValidator.verifyUserToken(token);
        if (StringUtils.isBlank(userId) || Constants.UNAUTHORIZED.equals(userId)) {
            return ProjectUtil.returnErrorMsg(Constants.INVALID_AUTH_TOKEN, HttpStatus.UNAUTHORIZED, response, Constants.FAILED);
        }

        try {
            String discussionId = (String) reportData.get(Constants.DISCUSSION_ID);
            String discussionText = (String) reportData.get(Constants.DISCUSSION_TEXT);
            String type = (String) reportData.get(Constants.TYPE);
            Object entityObject = Constants.ANSWER_POST_REPLY.equals(type)
                    ? discussionAnswerPostReplyRepository.findById(discussionId).orElse(null)
                    : discussionRepository.findById(discussionId).orElse(null);

            if (entityObject == null) {
                return ProjectUtil.returnErrorMsg(Constants.DISCUSSION_NOT_FOUND, HttpStatus.NOT_FOUND, response, Constants.FAILED);
            }

            JsonNode dataNode;
            Boolean isActive;
            if (Constants.ANSWER_POST_REPLY.equals(type)) {
                DiscussionAnswerPostReplyEntity replyEntity = (DiscussionAnswerPostReplyEntity) entityObject;
                dataNode = replyEntity.getData();
                ((ObjectNode) dataNode).put(IS_PROFANE, replyEntity.getIsProfane());
                log.info("Profanity status for the reply: {} for discussionId : {} ", replyEntity.getIsProfane(), replyEntity.getDiscussionId());
                isActive = replyEntity.getIsActive();
            } else {
                DiscussionEntity discussionEntity = (DiscussionEntity) entityObject;
                dataNode = discussionEntity.getData();
                ((ObjectNode) dataNode).put(IS_PROFANE, discussionEntity.getIsProfane());
                log.info("Profanity status for the post: {} for discussionId : {}", discussionEntity.getIsProfane(), discussionEntity.getDiscussionId());
                isActive = discussionEntity.getIsActive();
            }

            ObjectNode data = (ObjectNode) dataNode;
            if (!isActive) {
                return ProjectUtil.returnErrorMsg(Constants.DISCUSSION_IS_INACTIVE, HttpStatus.CONFLICT, response, Constants.FAILED);
            }

            if (!type.equals(data.get(Constants.TYPE).asText())) {
                return ProjectUtil.returnErrorMsg(Constants.INVALID_TYPE + type, HttpStatus.BAD_REQUEST, response, Constants.FAILED);
            }

            if (Constants.SUSPENDED.equals(data.get(Constants.STATUS).asText())) {
                return ProjectUtil.returnErrorMsg(Constants.DISCUSSION_SUSPENDED, HttpStatus.CONFLICT, response, Constants.FAILED);
            }

            // Check if the user has already reported the discussion
            Map<String, Object> reportCheckData = new HashMap<>();
            reportCheckData.put(Constants.USERID, userId);
            reportCheckData.put(Constants.DISCUSSION_ID, discussionId);
            List<Map<String, Object>> existingReports = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                    Constants.KEYSPACE_SUNBIRD, Constants.DISCUSSION_POST_REPORT_LOOKUP_BY_USER, reportCheckData, null, null);

            if (!existingReports.isEmpty()) {
                return ProjectUtil.returnErrorMsg("User has already reported this post", HttpStatus.ALREADY_REPORTED, response, Constants.SUCCESS);
            }

            // Store user data in Cassandra
            Map<String, Object> userReportData = new HashMap<>();
            userReportData.put(Constants.USERID, userId);
            userReportData.put(Constants.DISCUSSION_ID, discussionId);
            if (StringUtils.isNotBlank(discussionText)) {
                userReportData.put(Constants.DISCUSSION_TEXT, discussionText);
            }
            if (reportData.containsKey(Constants.REPORTED_REASON)) {
                List<String> reportedReasonList = (List<String>) reportData.get(Constants.REPORTED_REASON);
                if (reportedReasonList != null && !reportedReasonList.isEmpty()) {
                    StringBuilder reasonBuilder = new StringBuilder(String.join(", ", reportedReasonList));

                    if (reportedReasonList.contains(Constants.OTHERS) && reportData.containsKey(Constants.OTHER_REASON)) {
                        reasonBuilder.append(", ").append(reportData.get(Constants.OTHER_REASON));
                    }
                    userReportData.put(Constants.REASON, reasonBuilder.toString());
                }
            }
            Instant now = Instant.now();
            Timestamp currentTime = Timestamp.from(now);
            data.put(Constants.RECENT_REPORTED_ON, getFormattedCurrentTime(currentTime));
            userReportData.put(Constants.CREATED_ON, now);
            cassandraOperation.insertRecord(Constants.KEYSPACE_SUNBIRD, Constants.DISCUSSION_POST_REPORT_LOOKUP_BY_USER, userReportData);
            cassandraOperation.insertRecord(Constants.KEYSPACE_SUNBIRD, Constants.DISCUSSION_POST_REPORT_LOOKUP_BY_POST, userReportData);

            // Update the status of the discussion in Cassandra
            String status;
            if (cbServerProperties.isDiscussionReportHidePost()) {
                List<Map<String, Object>> reportedByUsers = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                        Constants.KEYSPACE_SUNBIRD, Constants.DISCUSSION_POST_REPORT_LOOKUP_BY_POST, Collections.singletonMap(Constants.DISCUSSION_ID, discussionId), null, null);
                status = CollectionUtils.isNotEmpty(reportedByUsers) && reportedByUsers.size() >= cbServerProperties.getReportPostUserLimit()
                        ? Constants.SUSPENDED
                        : Constants.REPORTED;
            } else {
                status = Constants.REPORTED;
            }

            ObjectNode jsonNode = objectMapper.createObjectNode();
            if (!data.get(Constants.STATUS).textValue().equals(status)) {
                data.put(Constants.STATUS, status);
            }

            ArrayNode reportedByArray;
            if (data.has(Constants.REPORTED_BY)) {
                reportedByArray = (ArrayNode) data.get(Constants.REPORTED_BY);
            } else {
                reportedByArray = objectMapper.createArrayNode();
                data.set(Constants.REPORTED_BY, reportedByArray);
            }
            reportedByArray.add(userId);
            if (Constants.ANSWER_POST_REPLY.equals(type)) {
                DiscussionAnswerPostReplyEntity replyEntity = (DiscussionAnswerPostReplyEntity) entityObject;
                replyEntity.setData(dataNode);
                discussionAnswerPostReplyRepository.save(replyEntity);
            } else {
                DiscussionEntity discussionEntity = (DiscussionEntity) entityObject;
                discussionEntity.setData(dataNode);
                discussionRepository.save(discussionEntity);
            }
            jsonNode.setAll(data);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(cbServerProperties.getDiscussionEntity(), discussionId, map, cbServerProperties.getElasticDiscussionJsonPath());
            cacheService.putCache(Constants.DISCUSSION_CACHE_PREFIX + discussionId, jsonNode);

            if (Constants.ANSWER_POST_REPLY.equals(type)) {
                redisTemplate.opsForValue()
                        .getAndDelete(discussionServiceUtil.generateRedisJwtTokenKey(createDefaultSearchCriteria(
                                data.get(Constants.PARENT_ANSWER_POST_ID).asText(),
                                data.get(Constants.COMMUNITY_ID).asText())));
            } else {
                deleteCacheByCommunity(Constants.DISCUSSION_CACHE_PREFIX + data.get(Constants.COMMUNITY_ID).asText());
                updateCacheForFirstFivePages(data.get(Constants.COMMUNITY_ID).asText(), false);
                updateCacheForGlobalFeed(userId);
            }
            if (status.equals(Constants.SUSPENDED)) {
                deleteCacheByCommunity(SUSPENDED_POSTS_CACHE_PREFIX + data.get(Constants.COMMUNITY_ID).asText());
                deleteCacheByCommunity(ALL_REPORTED_POSTS_CACHE_PREFIX + data.get(Constants.COMMUNITY_ID).asText());
            }
            if (status.equals(REPORTED)) {
                deleteCacheByCommunity(ALL_REPORTED_POSTS_CACHE_PREFIX + data.get(COMMUNITY_ID).asText());
            }
            if (QUESTION.equals(type)) {
                deleteCacheByCommunity(REPORTED_QUESTION_POSTS_CACHE_PREFIX + data.get(COMMUNITY_ID).asText());
            } else if (ANSWER_POST.equals(type)) {
                deleteCacheByCommunity(REPORTED_ANSWER_POST_POSTS_CACHE_PREFIX + data.get(COMMUNITY_ID).asText());
            } else if (ANSWER_POST_REPLY.equals(type)) {
                deleteCacheByCommunity(REPORTED_ANSWER_POST_REPLY_POSTS_CACHE_PREFIX + data.get(COMMUNITY_ID).asText());
            }
            String redisKey = Constants.REPORT_STATISTICS_CACHE_PREFIX + discussionId;
            cacheService.deleteCache(redisKey);
            log.info("Updated cache for global feed");
            map.put(Constants.DISCUSSION_ID, reportData.get(Constants.DISCUSSION_ID));
            response.setResult(map);
            return response;
        } catch (Exception e) {
            log.error("DiscussionService::report: Failed to report discussion", e);
            return ProjectUtil.returnErrorMsg(Constants.DISCUSSION_REPORT_FAILED, HttpStatus.INTERNAL_SERVER_ERROR, response, Constants.FAILED);
        }
    }

    private String validateReportPayload(Map<String, Object> reportData) {
        StringBuffer errorMsg = new StringBuffer();
        List<String> errList = new ArrayList<>();

        if (reportData.containsKey(Constants.DISCUSSION_ID) && StringUtils.isBlank((String) reportData.get(Constants.DISCUSSION_ID))) {
            errList.add(Constants.DISCUSSION_ID);
        }
        if (reportData.containsKey(Constants.TYPE) && StringUtils.isBlank((String) reportData.get(Constants.TYPE))) {
            errList.add(Constants.TYPE);
        }
        if (reportData.containsKey(Constants.REPORTED_REASON)) {
            Object reportedReasonObj = reportData.get(Constants.REPORTED_REASON);
            if (reportedReasonObj instanceof List) {
                List<String> reportedReasonList = (List<String>) reportedReasonObj;
                if (reportedReasonList.isEmpty()) {
                    errList.add(Constants.REPORTED_REASON);
                } else if (reportedReasonList.contains(Constants.OTHERS)) {
                    if (!reportData.containsKey(Constants.OTHER_REASON) ||
                            StringUtils.isBlank((String) reportData.get(Constants.OTHER_REASON))) {
                        errList.add(Constants.OTHER_REASON);
                    }
                }
            } else {
                errList.add(Constants.REPORTED_REASON);
            }
        }
        if (!errList.isEmpty()) {
            errorMsg.append("Failed Due To Missing Params - ").append(errList).append(".");
        }
        return errorMsg.toString();
    }


    @Override
    public ApiResponse uploadFile(MultipartFile mFile, String communityId, String discussionId) {
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.DISCUSSION_UPLOAD_FILE);
        if (mFile.isEmpty()) {
            return ProjectUtil.returnErrorMsg(Constants.DISCUSSION_FILE_EMPTY, HttpStatus.BAD_REQUEST, response, Constants.FAILED);
        }
        if (StringUtils.isBlank(discussionId)) {
            return ProjectUtil.returnErrorMsg(Constants.INVALID_DISCUSSION_ID, HttpStatus.BAD_REQUEST, response, Constants.FAILED);
        }
        if (StringUtils.isBlank(communityId)) {
            return ProjectUtil.returnErrorMsg(Constants.INVALID_COMMUNITY_ID, HttpStatus.BAD_REQUEST, response, Constants.FAILED);
        }

        File file = null;
        try {
            file = new File(System.currentTimeMillis() + Constants.UNDER_SCORE + mFile.getOriginalFilename());

            file.createNewFile();
            // Use try-with-resources to ensure FileOutputStream is closed
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(mFile.getBytes());
            }

            String uploadFolderPath = cbServerProperties.getDiscussionCloudFolderName() + "/" + communityId + "/" + discussionId;
            return uploadFile(file, uploadFolderPath, cbServerProperties.getDiscussionContainerName());
        } catch (Exception e) {
            log.error("Failed to upload file. Exception: ", e);
            response.getParams().setStatus(Constants.FAILED);
            response.getParams().setErrMsg("Failed to upload file. Exception: " + e.getMessage());
            response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return response;
        } finally {
            if (file != null && file.exists()) {
                file.delete();
            }
        }
    }

    public ApiResponse uploadFile(File file, String cloudFolderName, String containerName) {
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.UPLOAD_FILE);
        try {
            String objectKey = cloudFolderName + "/" + file.getName();
            String url = storageService.upload(containerName, file.getAbsolutePath(),
                    objectKey, Option.apply(false), Option.apply(1), Option.apply(5), Option.empty());
            Map<String, String> uploadedFile = new HashMap<>();
            uploadedFile.put(Constants.NAME, file.getName());
            uploadedFile.put(Constants.URL, url);
            response.getResult().putAll(uploadedFile);
            return response;
        } catch (Exception e) {
            log.error("Failed to upload file. Exception: ", e);
            response.getParams().setStatus(Constants.FAILED);
            response.getParams().setErrMsg("Failed to upload file. Exception: " + e.getMessage());
            response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return response;
        }
    }

    private void updateMetricsDbOperation(String apiName, String dbType, String operationType, long time) {
        if (ApiMetricsTracker.isTrackingEnabled()) {
            ApiMetricsTracker.recordDbOperation(apiName, dbType, operationType, System.currentTimeMillis() - time);
        }
    }

    private void updateMetricsApiCall(String apiName) {
        if (ApiMetricsTracker.isTrackingEnabled()) {
            ApiMetricsTracker.recordApiCall(apiName);
        }
    }

    private boolean validateCommunityId(String communityId) {
        Optional<CommunityEntity> communityEntityOptional = communityEngagementRepository.findByCommunityIdAndIsActive(communityId, true);
        if (communityEntityOptional.isPresent()) {
            return true;
        }
        return false;
    }

    @Override
    public ApiResponse updateAnswerPost(JsonNode answerPostData, String token) {
        log.info("DiscussionService::updateAnswerPost:updating answerPost");
        ApiResponse response = ProjectUtil.createDefaultResponse("discussion.updateAnswerPost");
        payloadValidation.validatePayload(Constants.ANSWER_POST_UPDATE_VALIDATION_SCHEMA, answerPostData);
        String userId = accessTokenValidator.verifyUserToken(token);
        if (StringUtils.isBlank(userId) || userId.equals(Constants.UNAUTHORIZED)) {
            response.getParams().setErrMsg(Constants.INVALID_AUTH_TOKEN);
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return response;
        }
        updateMetricsApiCall(Constants.DISCUSSION_ANSWER_POST);
        long redisTimer = System.currentTimeMillis();
        DiscussionEntity discussionEntity = discussionRepository.findById(answerPostData.get(Constants.ANSWER_POST_ID).asText()).orElse(null);
        updateMetricsDbOperation(Constants.DISCUSSION_ANSWER_POST, Constants.POSTGRES, Constants.READ, redisTimer);
        if (discussionEntity == null || !discussionEntity.getIsActive()) {
            return ProjectUtil.returnErrorMsg(Constants.INVALID_DISCUSSION_ID, HttpStatus.BAD_REQUEST, response, Constants.FAILED);
        }
        ObjectNode data = (ObjectNode) discussionEntity.getData();
        String type = data.get(Constants.TYPE).asText();
        if (!type.equals(Constants.ANSWER_POST)) {
            return ProjectUtil.returnErrorMsg(Constants.INVALID_ANSWER_POST_ID, HttpStatus.BAD_REQUEST, response, Constants.FAILED);
        }
        if (data.get(Constants.STATUS).asText().equals(Constants.SUSPENDED)) {
            return ProjectUtil.returnErrorMsg(Constants.DISCUSSION_SUSPENDED, HttpStatus.BAD_REQUEST, response, Constants.FAILED);
        }

        try {
            ObjectNode answerPostDataNode = (ObjectNode) answerPostData;
            Set<String> existingMentionedUserIds = new HashSet<>();
            data.withArray(MENTIONED_USERS).forEach(userNode -> {
                String userid = userNode.path(USER_ID_RQST).asText(null);
                if (StringUtils.isNotBlank(userid)) existingMentionedUserIds.add(userid);
            });
            Set<String> seenUserIdsInRequest = new HashSet<>();
            List<String> newlyAddedUserIds = new ArrayList<>();
            ArrayNode uniqueMentionedUsers = objectMapper.createArrayNode();
            JsonNode incomingMentionedUsers = answerPostData.path(MENTIONED_USERS);
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
            answerPostDataNode.set(MENTIONED_USERS, uniqueMentionedUsers);

            answerPostDataNode.remove(Constants.ANSWER_POST_ID);

            if (!answerPostDataNode.has(Constants.IS_INITIAL_UPLOAD) || !answerPostDataNode.get(Constants.IS_INITIAL_UPLOAD).asBoolean()) {
                Timestamp currentTime = new Timestamp(System.currentTimeMillis());
                answerPostDataNode.put(Constants.UPDATED_ON, getFormattedCurrentTime(currentTime));
                discussionEntity.setUpdatedOn(currentTime);
            }
            data.setAll(answerPostDataNode);
            discussionEntity.setData(data);
            discussionEntity.setIsProfane(false);
            long timer = System.currentTimeMillis();
            discussionRepository.save(discussionEntity);
            updateMetricsDbOperation(Constants.DISCUSSION_ANSWER_POST, Constants.POSTGRES, Constants.UPDATE, timer);

            ObjectNode jsonNode = objectMapper.createObjectNode();
            jsonNode.setAll(data);
            Map<String, Object> discussionAnswerPostDetailMap = objectMapper.convertValue(jsonNode, Map.class);
            if(MapUtils.isNotEmpty(discussionAnswerPostDetailMap)) {
                discussionAnswerPostDetailMap.put(IS_PROFANE, false);
            }
            esUtilService.updateDocument(cbServerProperties.getDiscussionEntity(), discussionEntity.getDiscussionId(), discussionAnswerPostDetailMap, cbServerProperties.getElasticDiscussionJsonPath());
            cacheService.putCache(Constants.DISCUSSION_CACHE_PREFIX + String.valueOf(discussionEntity.getDiscussionId()), jsonNode);
            redisTemplate.opsForValue()
                    .getAndDelete(discussionServiceUtil.generateRedisJwtTokenKey(createSearchCriteriaWithDefaults(
                            data.get(Constants.PARENT_DISCUSSION_ID).asText(),
                            data.get(Constants.COMMUNITY_ID).asText(),
                            Constants.ANSWER_POST)));
            log.info("AnswerPost updated successfully");
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
                log.error("Error while triggering notification", e);
            }
            if(MapUtils.isNotEmpty(discussionAnswerPostDetailMap)) {
                discussionAnswerPostDetailMap.remove(IS_PROFANE);
                discussionAnswerPostDetailMap.remove(Constants.PROFANITY_RESPONSE);
            }
            response.setResponseCode(HttpStatus.OK);
            response.getParams().setStatus(Constants.SUCCESS);
            response.setResult(discussionAnswerPostDetailMap);
            producer.push(cbServerProperties.getKafkaProcessDetectLanguageTopic(), jsonNode);
        } catch (Exception e) {
            log.error("Failed to update AnswerPost: {}", e.getMessage(), e);
            DiscussionServiceUtil.createErrorResponse(response, Constants.FAILED_TO_UPDATE_ANSWER_POST, HttpStatus.INTERNAL_SERVER_ERROR, Constants.FAILED);
            return response;
        }
        return response;
    }

    @Override
    public ApiResponse bookmarkDiscussion(String token, String communityId, String discussionId) {
        log.info("DiscussionService::bookmarkDiscussion: Bookmarking discussion");
        ApiResponse response = ProjectUtil.createDefaultResponse("discussion.bookmarkDiscussion");
        if (StringUtils.isBlank(discussionId)) {
            return ProjectUtil.returnErrorMsg(Constants.INVALID_DISCUSSION_ID, HttpStatus.BAD_REQUEST, response, Constants.FAILED);
        }

        if (StringUtils.isBlank(communityId)) {
            return ProjectUtil.returnErrorMsg(Constants.INVALID_COMMUNITY_ID, HttpStatus.BAD_REQUEST, response, Constants.FAILED);
        }
        String userId = accessTokenValidator.verifyUserToken(token);
        if (StringUtils.isBlank(userId) || Constants.UNAUTHORIZED.equals(userId)) {
            return ProjectUtil.returnErrorMsg(Constants.INVALID_AUTH_TOKEN, HttpStatus.UNAUTHORIZED, response, Constants.FAILED);
        }

        try {
            Optional<DiscussionEntity> discussionDbData = discussionRepository.findById(discussionId);
            if (!discussionDbData.isPresent()) {
                return ProjectUtil.returnErrorMsg(Constants.DISCUSSION_NOT_FOUND, HttpStatus.NOT_FOUND, response, Constants.FAILED);
            }
            DiscussionEntity discussionEntity = discussionDbData.get();
            if (!discussionEntity.getIsActive()) {
                return ProjectUtil.returnErrorMsg(Constants.DISCUSSION_IS_INACTIVE, HttpStatus.CONFLICT, response, Constants.FAILED);
            }

            String bookMarkedCommunityId = discussionEntity.getData().get(Constants.COMMUNITY_ID).asText();
            if (!bookMarkedCommunityId.equals(communityId)) {
                return ProjectUtil.returnErrorMsg(Constants.INVALID_COMMUNITY_ID, HttpStatus.BAD_REQUEST, response, Constants.FAILED);
            }

            Map<String, Object> properties = new HashMap<>();
            properties.put(Constants.USERID, userId);
            properties.put(Constants.COMMUNITY_ID, communityId);
            properties.put(Constants.DISCUSSION_ID, discussionId);

            // Check if the bookmark already exists
            List<Map<String, Object>> existingBookmarks = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                    Constants.KEYSPACE_SUNBIRD, Constants.DISCUSSION_BOOKMARKS, properties, Arrays.asList(Constants.STATUS), null);

            if (!existingBookmarks.isEmpty() && (boolean) existingBookmarks.get(0).get(Constants.STATUS)) {
                return ProjectUtil.returnErrorMsg(Constants.ALREADY_BOOKMARKED, HttpStatus.ALREADY_REPORTED, response, Constants.FAILED);
            }

            // Insert the new bookmark
            properties.put(Constants.CREATED_ON, Instant.now());
            properties.put(Constants.STATUS, true);
            cassandraOperation.insertRecord(Constants.KEYSPACE_SUNBIRD, Constants.DISCUSSION_BOOKMARKS, properties);
            cacheService.deleteCache(Constants.DISCUSSION_CACHE_PREFIX + Constants.COMMUNITY + communityId + userId);
            Map<String, Object> map = new HashMap<>();
            map.put(Constants.CREATED_ON, properties.get(Constants.CREATED_ON));
            map.put(Constants.COMMUNITY_ID, communityId);
            map.put(Constants.DISCUSSION_ID, discussionId);
            response.setResult(map);
            return response;
        } catch (Exception e) {
            log.error("DiscussionService::bookmarkDiscussion: Failed to bookmark discussion", e);
            return ProjectUtil.returnErrorMsg(Constants.DISCUSSION_BOOKMARK_FAILED, HttpStatus.INTERNAL_SERVER_ERROR, response, Constants.FAILED);
        }
    }

    public ApiResponse unBookmarkDiscussion(String communityId, String discussionId, String token) {
        log.info("DiscussionService::unBookmarkDiscussion: UnBookmarking discussion");
        ApiResponse response = ProjectUtil.createDefaultResponse("discussion.unBookmarkDiscussion");
        if (StringUtils.isBlank(discussionId)) {
            return ProjectUtil.returnErrorMsg(Constants.INVALID_DISCUSSION_ID, HttpStatus.BAD_REQUEST, response, Constants.FAILED);
        }

        if (StringUtils.isBlank(communityId)) {
            return ProjectUtil.returnErrorMsg(Constants.INVALID_COMMUNITY_ID, HttpStatus.BAD_REQUEST, response, Constants.FAILED);
        }
        String userId = accessTokenValidator.verifyUserToken(token);
        if (StringUtils.isBlank(userId) || Constants.UNAUTHORIZED.equals(userId)) {
            return ProjectUtil.returnErrorMsg(Constants.INVALID_AUTH_TOKEN, HttpStatus.UNAUTHORIZED, response, Constants.FAILED);
        }

        Map<String, Object> compositeKeys = new HashMap<>();
        Map<String, Object> properties = new HashMap<>();
        compositeKeys.put(Constants.COMMUNITY_ID, communityId);
        compositeKeys.put(Constants.DISCUSSION_ID, discussionId);
        compositeKeys.put(Constants.USERID, userId);
        properties.put(Constants.STATUS, false);

        try {
            cassandraOperation.updateRecordByCompositeKey(Constants.KEYSPACE_SUNBIRD, Constants.DISCUSSION_BOOKMARKS, properties, compositeKeys);
            cacheService.deleteCache(Constants.DISCUSSION_CACHE_PREFIX + Constants.COMMUNITY + communityId + userId);
            return response;
        } catch (Exception e) {
            log.error("DiscussionService::unBookmarkDiscussion: Failed to unBookmark discussion", e);
            return ProjectUtil.returnErrorMsg(Constants.DISCUSSION_UN_BOOKMARK_FAILED, HttpStatus.INTERNAL_SERVER_ERROR, response, Constants.FAILED);
        }
    }

    @Override
    public ApiResponse getBookmarkedDiscussions(String token, Map<String, Object> requestData) {
        log.info("DiscussionService::getBookmarkedDiscussions: Fetching bookmarked discussions");
        ApiResponse response = ProjectUtil.createDefaultResponse("discussion.getBookmarkedDiscussions");
        String errorMsg = validateGetBookmarkedDiscussions(requestData);

        if (StringUtils.isNotBlank(errorMsg)) {
            return ProjectUtil.returnErrorMsg(errorMsg, HttpStatus.BAD_REQUEST, response, Constants.FAILED);
        }
        String userId = accessTokenValidator.verifyUserToken(token);
        if (StringUtils.isBlank(userId) || Constants.UNAUTHORIZED.equals(userId)) {
            return ProjectUtil.returnErrorMsg(Constants.INVALID_AUTH_TOKEN, HttpStatus.UNAUTHORIZED, response, Constants.FAILED);
        }
        try {
            List<String> cachedKeys = new ArrayList<>();
            String cachedJson = cacheService.getCache(Constants.DISCUSSION_CACHE_PREFIX + Constants.COMMUNITY + requestData.get(Constants.COMMUNITY_ID) + userId);
            if (StringUtils.isNotBlank(cachedJson)) {
                cachedKeys = objectMapper.readValue(cachedJson, new TypeReference<List<String>>() {
                });
            } else {
                Map<String, Object> properties = new HashMap<>();
                properties.put(Constants.USERID, userId);
                properties.put(Constants.COMMUNITY_ID, requestData.get(Constants.COMMUNITY_ID));
                List<Map<String, Object>> bookmarkedDiscussions = cassandraOperation.getRecordsByPropertiesWithoutFiltering(Constants.KEYSPACE_SUNBIRD, Constants.DISCUSSION_BOOKMARKS, properties, Arrays.asList(Constants.DISCUSSION_ID, Constants.STATUS), null);
                if (bookmarkedDiscussions.isEmpty()) {
                    return ProjectUtil.returnErrorMsg(Constants.NO_DISCUSSIONS_FOUND, HttpStatus.OK, response, Constants.SUCCESS);
                }
                for (Map<String, Object> bookmarkedDiscussion : bookmarkedDiscussions) {
                    if (Boolean.TRUE.equals(bookmarkedDiscussion.get(Constants.STATUS))) {
                        cachedKeys.add((String) bookmarkedDiscussion.get(Constants.DISCUSSION_ID_KEY));
                    }
                }
                if (cachedKeys.isEmpty()) {
                    return ProjectUtil.returnErrorMsg(Constants.NO_DISCUSSIONS_FOUND, HttpStatus.OK, response, Constants.SUCCESS);
                }
                cacheService.putCache(Constants.DISCUSSION_CACHE_PREFIX + Constants.COMMUNITY + requestData.get(Constants.COMMUNITY_ID) + userId, cachedKeys);
            }

            SearchCriteria searchCriteria = new SearchCriteria();
            HashMap<String, Object> filterCriteria = new HashMap<>();
            filterCriteria.put(Constants.DISCUSSION_ID, cachedKeys);
            searchCriteria.setFilterCriteriaMap(filterCriteria);
            searchCriteria.setPageNumber((int) requestData.get(Constants.PAGE));
            searchCriteria.setPageSize((int) requestData.get(Constants.PAGE_SIZE));
            searchCriteria.setOrderDirection(Constants.DESC);
            searchCriteria.setOrderBy(Constants.CREATED_ON);
            searchCriteria.getFilterCriteriaMap().put(IS_PROFANE, false);
            if (requestData.containsKey(Constants.SEARCH_STRING) && StringUtils.isNotBlank((String) requestData.get(Constants.SEARCH_STRING))) {
                if (((String) requestData.get(Constants.SEARCH_STRING)).length() < 3) {
                    DiscussionServiceUtil.createErrorResponse(response, Constants.MINIMUM_CHARACTERS_NEEDED, HttpStatus.BAD_REQUEST, Constants.FAILED_CONST);
                    return response;
                }
                searchCriteria.setSearchString((String) requestData.get(Constants.SEARCH_STRING));
            }

            SearchResult searchResult = redisTemplate.opsForValue().get(discussionServiceUtil.generateRedisJwtTokenKey(searchCriteria));
            if (searchResult == null) {
                searchResult = esUtilService.searchDocuments(cbServerProperties.getDiscussionEntity(), searchCriteria, cbServerProperties.getElasticDiscussionJsonPath());
                List<Map<String, Object>> data = searchResult.getData();
                fetchAndEnhanceDiscussions(data, false);
                searchResult.setData(data);
                redisTemplate.opsForValue().set(discussionServiceUtil.generateRedisJwtTokenKey(searchCriteria), searchResult, cbServerProperties.getSearchResultRedisTtl(), TimeUnit.SECONDS);
            }

            HashMap<String, Object> result = new HashMap<>();
            result.put(Constants.SEARCH_RESULTS, searchResult);
            response.setResult(result);
            return response;
        } catch (Exception e) {
            log.error("DiscussionService::getBookmarkedDiscussions: Failed to fetch bookmarked discussions", e);
            return ProjectUtil.returnErrorMsg(Constants.DISCUSSION_BOOKMARK_FETCH_FAILED, HttpStatus.INTERNAL_SERVER_ERROR, response, Constants.FAILED);
        }
    }

    private String validateGetBookmarkedDiscussions(Map<String, Object> requestData) {
        if (requestData == null) {
            return Constants.MISSING_REQUEST_DATA;
        }

        StringBuffer errorMsg = new StringBuffer();
        List<String> errList = new ArrayList<>();

        if (!requestData.containsKey(Constants.COMMUNITY_ID) && StringUtils.isBlank((String) requestData.get(Constants.COMMUNITY_ID))) {
            errList.add(Constants.COMMUNITY_ID);
        }
        if (!requestData.containsKey(Constants.PAGE) || !(requestData.get(Constants.PAGE) instanceof Integer)) {
            errList.add(Constants.PAGE);
        }
        if (!requestData.containsKey(Constants.PAGE_SIZE) || !(requestData.get(Constants.PAGE_SIZE) instanceof Integer)) {
            errList.add(Constants.PAGE_SIZE);
        }
        if (!errList.isEmpty()) {
            errorMsg.append("Failed Due To Missing Params - ").append(errList).append(".");
        }
        return errorMsg.toString();
    }

    private void fetchAndEnhanceDiscussions(List<Map<String, Object>> discussions, boolean isAnswerPost) {
        Map<String, String> discussionToCreatedByMap = discussions.stream()
                .collect(Collectors.toMap(
                        discussion -> discussion.get(Constants.DISCUSSION_ID).toString(),
                        discussion -> discussion.get(Constants.CREATED_BY).toString()));

        Map<String, List<String>> discussionToUserTagMap = new HashMap<>();
        if (isAnswerPost) {
            discussionToUserTagMap.putAll(discussions.stream()
                    .filter(discussion -> discussion.containsKey(Constants.TAGGED_USER))
                    .collect(Collectors.toMap(
                            discussion -> discussion.get(Constants.DISCUSSION_ID).toString(),
                            discussion -> (List<String>) discussion.get(Constants.TAGGED_USER))));
        }

        Set<String> createdByIds = new HashSet<>(discussionToCreatedByMap.values());
        Set<String> userTagIds = new HashSet<>();
        if (!discussionToUserTagMap.isEmpty()) {
            userTagIds = discussionToUserTagMap.values().stream()
                    .flatMap(Collection::stream)
                    .collect(Collectors.toSet());
        }
        Set<String> allUserIds = new HashSet<>();
        allUserIds.addAll(createdByIds);
        allUserIds.addAll(userTagIds);

        long userDataRedisTime = System.currentTimeMillis();
        List<Object> redisResults = fetchDataForKeys(
                allUserIds.stream().map(id -> Constants.USER_PREFIX + id).collect(Collectors.toList()), true
        );
        updateMetricsDbOperation(Constants.DISCUSSION_SEARCH, Constants.REDIS, Constants.READ, userDataRedisTime);
        Map<String, Object> userDetailsMap = redisResults.stream()
                .map(user -> (Map<String, Object>) user)
                .collect(Collectors.toMap(
                        user -> user.get(Constants.USER_ID_KEY).toString(),
                        user -> user));

        List<String> missingUserIds = allUserIds.stream()
                .filter(id -> !userDetailsMap.containsKey(id))
                .collect(Collectors.toList());

        if (!missingUserIds.isEmpty()) {
            List<Object> cassandraResults = fetchUserFromPrimary(missingUserIds);
            userDetailsMap.putAll(cassandraResults.stream()
                    .map(user -> (Map<String, Object>) user)
                    .collect(Collectors.toMap(
                            user -> user.get(Constants.USER_ID_KEY).toString(),
                            user -> user)));
        }

        discussions.forEach(discussion -> {
            String discussionId = discussion.get(Constants.DISCUSSION_ID).toString();
            String createdById = discussionToCreatedByMap.get(discussionId);
            List<String> userTagIdsList = discussionToUserTagMap.get(discussionId);
            boolean hasCreatedBy = createdById != null && userDetailsMap.containsKey(createdById);
            if (hasCreatedBy) {
                Map<String, Object> userData = (Map<String, Object>) userDetailsMap.get(createdById);
                Object designationObj = userData.get(DESIGNATION_KEY);
                String designationStr = designationObj != null ? designationObj.toString() : "";
                if (designationStr.isBlank() || "null".equalsIgnoreCase(designationStr)) {
                    userData.put(DESIGNATION_KEY, "");
                }
                discussion.put(Constants.CREATED_BY, userDetailsMap.get(createdById));
            }
            if (isAnswerPost && userTagIdsList != null && !userTagIdsList.isEmpty()) {
                List<Object> userTags = userTagIdsList.stream()
                        .filter(userDetailsMap::containsKey)
                        .map(userDetailsMap::get)
                        .collect(Collectors.toList());
                discussion.put(Constants.TAGGED_USER, userTags);
            }
        });
    }

    @Override
    public ApiResponse searchDiscussionByCommunity(Map<String, Object> searchData) {
        log.info("DiscussionServiceImpl::searchDiscussionByCommunity");
        ApiResponse response = ProjectUtil.createDefaultResponse("search.discussion.by.community");
        String error = validateCommunitySearchRequest(searchData);
        if (StringUtils.isNotEmpty(error)) {
            DiscussionServiceUtil.createErrorResponse(response, error, HttpStatus.BAD_REQUEST, Constants.FAILED_CONST);
            return response;
        }

        try {
            String cacheKey = Constants.DISCUSSION_CACHE_PREFIX + searchData.get(Constants.COMMUNITY_ID) + Constants.UNDER_SCORE + searchData.get(Constants.PAGE_NUMBER);
            SearchResult searchResult = redisTemplate.opsForValue().get(cacheKey);

            if (searchResult != null) {
                log.info("DiscussionServiceImpl::searchDiscussionByCommunity: search result fetched from redis");
                response.getResult().put(Constants.SEARCH_RESULTS, searchResult);
                DiscussionServiceUtil.createSuccessResponse(response);
                return response;
            }

            SearchCriteria searchCriteria = getCriteria((int) searchData.get(Constants.PAGE_NUMBER), cbServerProperties.getDiscussionEsDefaultPageSize());
            Map<String, Object> filterCriteria = new HashMap<>();
            filterCriteria.put(Constants.COMMUNITY_ID, searchData.get(Constants.COMMUNITY_ID));
            filterCriteria.put(Constants.TYPE, Constants.QUESTION);
            filterCriteria.put(Constants.STATUS, Arrays.asList(Constants.ACTIVE, Constants.REPORTED));
            filterCriteria.put(Constants.IS_ACTIVE, true);
            filterCriteria.put(IS_PROFANE, false);
            searchCriteria.getFilterCriteriaMap().putAll(filterCriteria);
            searchResult = esUtilService.searchDocuments(cbServerProperties.getDiscussionEntity(), searchCriteria, cbServerProperties.getElasticDiscussionJsonPath());
            List<Map<String, Object>> discussions = searchResult.getData();

            if (searchCriteria.getRequestedFields().contains(Constants.CREATED_BY) || searchCriteria.getRequestedFields().isEmpty()) {
                fetchAndEnhanceDiscussions(discussions, false);
            }

            searchResult.setData(discussions);
            redisTemplate.opsForValue().set(cacheKey, searchResult, cbServerProperties.getDiscussionFeedRedisTtl(), TimeUnit.SECONDS);
            response.getResult().put(Constants.SEARCH_RESULTS, searchResult);
            DiscussionServiceUtil.createSuccessResponse(response);
            return response;
        } catch (Exception e) {
            log.error("error while searching discussion by community: {} .", e.getMessage(), e);
            DiscussionServiceUtil.createErrorResponse(response, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR, Constants.FAILED_CONST);
            return response;
        }
    }

    private SearchCriteria getCriteria(int pageNumber, int pageSize) {
        SearchCriteria searchCriteria = new SearchCriteria();
        searchCriteria.setPageNumber(pageNumber);
        searchCriteria.setPageSize(pageSize);
        searchCriteria.setOrderBy(Constants.CREATED_ON);
        searchCriteria.setOrderDirection(Constants.DESC);
        searchCriteria.setFilterCriteriaMap(new HashMap<>());
        searchCriteria.setRequestedFields(new ArrayList<>());
        return searchCriteria;
    }

    private String validateCommunitySearchRequest(Map<String, Object> searchData) {

        if (searchData == null) {
            return Constants.MISSING_REQUEST_DATA;
        }

        StringBuffer errorMsg = new StringBuffer();
        List<String> errList = new ArrayList<>();

        if (!searchData.containsKey(Constants.COMMUNITY_ID) || StringUtils.isBlank((String) searchData.get(Constants.COMMUNITY_ID))) {
            errList.add(Constants.COMMUNITY_ID);
        }
        if (!searchData.containsKey(Constants.PAGE_NUMBER) || !(searchData.get(Constants.PAGE_NUMBER) instanceof Integer)) {
            errList.add(Constants.PAGE_NUMBER);
        }
        if (!errList.isEmpty()) {
            errorMsg.append("Failed Due To Missing Params - ").append(errList).append(".");
        }
        return errorMsg.toString();
    }

    public void deleteCacheByCommunity(String prefix) {
        String pattern = prefix + "_*";
        Set<String> keys = redisTemplate.keys(pattern);
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.info("Deleted cache keys: {}", keys);
        } else {
            log.info("No cache keys found for pattern: {}", pattern);
        }
    }

    public void updateCacheForFirstFivePages(String communityId, boolean isDocumentType) {
        SearchCriteria searchCriteria = getCriteria(0, 5 * cbServerProperties.getDiscussionEsDefaultPageSize());
        Map<String, Object> filterCriteria = new HashMap<>();
        filterCriteria.put(Constants.COMMUNITY_ID, communityId);
        filterCriteria.put(Constants.TYPE, Constants.QUESTION);
        if (isDocumentType) {
            filterCriteria.put(Constants.CATEGORY_TYPE, Arrays.asList(Constants.DOCUMENT));
        }
        filterCriteria.put(Constants.STATUS, Arrays.asList(Constants.ACTIVE, Constants.REPORTED));
        filterCriteria.put(Constants.IS_ACTIVE, true);
        filterCriteria.put(IS_PROFANE, false);
        searchCriteria.getFilterCriteriaMap().putAll(filterCriteria);

        try {
            SearchResult searchResult = esUtilService.searchDocuments(cbServerProperties.getDiscussionEntity(), searchCriteria, cbServerProperties.getElasticDiscussionJsonPath());
            List<Map<String, Object>> discussions = searchResult.getData();

            if (searchCriteria.getRequestedFields().contains(Constants.CREATED_BY) || searchCriteria.getRequestedFields().isEmpty()) {
                fetchAndEnhanceDiscussions(discussions, false);
            }

            String cacheKeyPrefix;
            if (isDocumentType) {
                cacheKeyPrefix = Constants.DISCUSSION_DOCUMENT_POST + communityId + Constants.UNDER_SCORE;
            } else {
                cacheKeyPrefix = Constants.DISCUSSION_CACHE_PREFIX + communityId + Constants.UNDER_SCORE;
            }

            for (int pageNumber = 1; pageNumber <= 5; pageNumber++) {
                int fromIndex = (pageNumber - 1) * cbServerProperties.getDiscussionEsDefaultPageSize();
                if (fromIndex >= discussions.size()) {
                    break;
                }
                int toIndex = Math.min(fromIndex + cbServerProperties.getDiscussionEsDefaultPageSize(), discussions.size());
                List<Map<String, Object>> pageDiscussions = new ArrayList<>(discussions.subList(fromIndex, toIndex));

                String cacheKey = cacheKeyPrefix + (pageNumber - 1);
                SearchResult pageResult = new SearchResult();
                pageResult.setData(pageDiscussions);
                pageResult.setTotalCount(searchResult.getTotalCount());
                pageResult.setFacets(searchResult.getFacets());
                redisTemplate.opsForValue().set(cacheKey, pageResult, cbServerProperties.getDiscussionFeedRedisTtl(), TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.error("Error while updating cache for community {}: {}", communityId, e.getMessage(), e);
        }
    }

    private String getFormattedCurrentTime(Timestamp currentTime) {
        ZonedDateTime zonedDateTime = currentTime.toInstant().atZone(ZoneId.systemDefault());
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(Constants.TIME_FORMAT);
        return zonedDateTime.format(formatter);
    }

    private String generateRedisTokenKey(SearchCriteria searchCriteria) {
        if (searchCriteria != null) {

            try {
                if (searchCriteria.getFilterCriteriaMap() != null
                        && searchCriteria.getFilterCriteriaMap().size() == cbServerProperties.getUserFeedFilterCriteriaMapSize()
                        && searchCriteria.getFilterCriteriaMap().containsKey(Constants.CREATED_BY)
                        && searchCriteria.getFilterCriteriaMap().get(Constants.CREATED_BY) instanceof String
                        && searchCriteria.getFilterCriteriaMap().containsKey(Constants.COMMUNITY_ID)
                        && searchCriteria.getFilterCriteriaMap().get(Constants.COMMUNITY_ID) instanceof String) {

                    SearchCriteria tempSearchCriteria = objectMapper.readValue(cbServerProperties.getFilterCriteriaQuestionUserFeed(), SearchCriteria.class);
                    if (tempSearchCriteria != null && tempSearchCriteria.getFilterCriteriaMap() != null) {
                        tempSearchCriteria.getFilterCriteriaMap().put(Constants.COMMUNITY_ID, searchCriteria.getFilterCriteriaMap().get(Constants.COMMUNITY_ID));
                        tempSearchCriteria.getFilterCriteriaMap().put(Constants.CREATED_BY, searchCriteria.getFilterCriteriaMap().get(Constants.CREATED_BY));
                        tempSearchCriteria.setPageNumber(searchCriteria.getPageNumber());

                        if (tempSearchCriteria.equals(searchCriteria)) {
                            return Constants.DISCUSSION_POSTS_BY_USER
                                    + searchCriteria.getFilterCriteriaMap().get(Constants.COMMUNITY_ID)
                                    + Constants.UNDER_SCORE
                                    + searchCriteria.getFilterCriteriaMap().get(Constants.CREATED_BY)
                                    + Constants.UNDER_SCORE
                                    + searchCriteria.getPageNumber();
                        }
                    }
                }
                if (searchCriteria.getFilterCriteriaMap() != null
                        && searchCriteria.getFilterCriteriaMap().containsKey(Constants.COMMUNITY_ID)
                        && searchCriteria.getFilterCriteriaMap().get(Constants.COMMUNITY_ID) instanceof String
                        && searchCriteria.getFilterCriteriaMap().containsKey(Constants.CATEGORY_TYPE)) {
                    SearchCriteria tempSearchCriteria = objectMapper.readValue(cbServerProperties.getFilterCriteriaQuestionDocumentFeed(), SearchCriteria.class);
                    if (tempSearchCriteria != null && tempSearchCriteria.getFilterCriteriaMap() != null) {
                        tempSearchCriteria.getFilterCriteriaMap().put(Constants.COMMUNITY_ID, searchCriteria.getFilterCriteriaMap().get(Constants.COMMUNITY_ID));
                        tempSearchCriteria.setPageNumber(searchCriteria.getPageNumber());

                        if (tempSearchCriteria.equals(searchCriteria)) {
                            return Constants.DISCUSSION_DOCUMENT_POST + searchCriteria.getFilterCriteriaMap().get(Constants.COMMUNITY_ID)
                                    + Constants.UNDER_SCORE
                                    + searchCriteria.getPageNumber();
                        }
                    }
                }

                if (searchCriteria.getFilterCriteriaMap() != null
                        && searchCriteria.getFilterCriteriaMap().containsKey(Constants.COMMUNITY_ID)
                        && searchCriteria.getFilterCriteriaMap().get(Constants.COMMUNITY_ID) instanceof String
                        && searchCriteria.getFilterCriteriaMap().get(Constants.STATUS) instanceof List) {
                    SearchCriteria tempSearchCriteria = objectMapper.readValue(cbServerProperties.getMdoAllReportFeed(), SearchCriteria.class);
                    if (tempSearchCriteria != null && tempSearchCriteria.getFilterCriteriaMap() != null) {
                        tempSearchCriteria.getFilterCriteriaMap().put(Constants.COMMUNITY_ID, searchCriteria.getFilterCriteriaMap().get(Constants.COMMUNITY_ID));
                        tempSearchCriteria.setPageNumber(searchCriteria.getPageNumber());

                        if (tempSearchCriteria.equals(searchCriteria)) {
                            return Constants.ALL_REPORTED_POSTS_CACHE_PREFIX + searchCriteria.getFilterCriteriaMap().get(Constants.COMMUNITY_ID) + Constants.UNDER_SCORE + searchCriteria.getPageNumber();
                        }
                    }
                }

                if (searchCriteria.getFilterCriteriaMap() != null
                        && searchCriteria.getFilterCriteriaMap().containsKey(Constants.STATUS)
                        && searchCriteria.getFilterCriteriaMap().containsKey(Constants.COMMUNITY_ID)
                        && searchCriteria.getFilterCriteriaMap().get(Constants.COMMUNITY_ID) instanceof String) {

                    SearchCriteria tempSearchCriteria = objectMapper.readValue(cbServerProperties.getMdoQuestionReportFeed(), SearchCriteria.class);
                    if (tempSearchCriteria != null && tempSearchCriteria.getFilterCriteriaMap() != null) {
                        tempSearchCriteria.getFilterCriteriaMap().put(Constants.COMMUNITY_ID, searchCriteria.getFilterCriteriaMap().get(Constants.COMMUNITY_ID));
                        tempSearchCriteria.setPageNumber(searchCriteria.getPageNumber());

                        if (tempSearchCriteria.equals(searchCriteria)) {
                            return Constants.REPORTED_QUESTION_POSTS_CACHE_PREFIX + searchCriteria.getFilterCriteriaMap().get(Constants.COMMUNITY_ID) + Constants.UNDER_SCORE + searchCriteria.getPageNumber();
                        }
                    }
                }

                if (searchCriteria.getFilterCriteriaMap() != null
                        && searchCriteria.getFilterCriteriaMap().containsKey(Constants.STATUS)
                        && searchCriteria.getFilterCriteriaMap().containsKey(Constants.COMMUNITY_ID)
                        && searchCriteria.getFilterCriteriaMap().get(Constants.COMMUNITY_ID) instanceof String) {
                    SearchCriteria tempSearchCriteria = objectMapper.readValue(cbServerProperties.getMdoAnswerPostReportFeed(), SearchCriteria.class);
                    if (tempSearchCriteria != null && tempSearchCriteria.getFilterCriteriaMap() != null) {
                        tempSearchCriteria.getFilterCriteriaMap().put(Constants.COMMUNITY_ID, searchCriteria.getFilterCriteriaMap().get(Constants.COMMUNITY_ID));
                        tempSearchCriteria.setPageNumber(searchCriteria.getPageNumber());

                        if (tempSearchCriteria.equals(searchCriteria)) {
                            return Constants.REPORTED_ANSWER_POST_POSTS_CACHE_PREFIX + searchCriteria.getFilterCriteriaMap().get(Constants.COMMUNITY_ID) + Constants.UNDER_SCORE + searchCriteria.getPageNumber();
                        }
                    }
                }

                if (searchCriteria.getFilterCriteriaMap() != null
                        && searchCriteria.getFilterCriteriaMap().containsKey(Constants.STATUS)
                        && searchCriteria.getFilterCriteriaMap().containsKey(Constants.COMMUNITY_ID)
                        && searchCriteria.getFilterCriteriaMap().get(Constants.COMMUNITY_ID) instanceof String) {

                    SearchCriteria tempSearchCriteria = objectMapper.readValue(cbServerProperties.getMdoAnswerPostReplyReportFeed(), SearchCriteria.class);
                    if (tempSearchCriteria != null && tempSearchCriteria.getFilterCriteriaMap() != null) {
                        tempSearchCriteria.getFilterCriteriaMap().put(Constants.COMMUNITY_ID, searchCriteria.getFilterCriteriaMap().get(Constants.COMMUNITY_ID));
                        tempSearchCriteria.setPageNumber(searchCriteria.getPageNumber());

                        if (tempSearchCriteria.equals(searchCriteria)) {
                            return Constants.REPORTED_ANSWER_POST_REPLY_POSTS_CACHE_PREFIX + searchCriteria.getFilterCriteriaMap().get(Constants.COMMUNITY_ID) + Constants.UNDER_SCORE + searchCriteria.getPageNumber();
                        }
                    }
                }

                if (searchCriteria.getFilterCriteriaMap() != null
                        && searchCriteria.getFilterCriteriaMap().containsKey(Constants.STATUS)
                        && searchCriteria.getFilterCriteriaMap().containsKey(Constants.COMMUNITY_ID)
                        && searchCriteria.getFilterCriteriaMap().get(Constants.COMMUNITY_ID) instanceof String) {

                    SearchCriteria tempSearchCriteria = objectMapper.readValue(cbServerProperties.getMdoAllSuspendedFeed(), SearchCriteria.class);
                    if (tempSearchCriteria != null && tempSearchCriteria.getFilterCriteriaMap() != null) {
                        tempSearchCriteria.getFilterCriteriaMap().put(Constants.COMMUNITY_ID, searchCriteria.getFilterCriteriaMap().get(Constants.COMMUNITY_ID));
                        tempSearchCriteria.setPageNumber(searchCriteria.getPageNumber());

                        if (tempSearchCriteria.equals(searchCriteria)) {
                            return Constants.SUSPENDED_POSTS_CACHE_PREFIX + searchCriteria.getFilterCriteriaMap().get(Constants.COMMUNITY_ID) + Constants.UNDER_SCORE + searchCriteria.getPageNumber();
                        }
                    }
                }

                String reqJsonString = objectMapper.writeValueAsString(searchCriteria);
                return JWT.create().withClaim(Constants.REQUEST_PAYLOAD, reqJsonString).sign(Algorithm.HMAC256(cbServerProperties.getJwtDemandSearchKeyName()));
            } catch (JsonProcessingException e) {
                log.error("Error occurred while converting json object to json string", e);
            }
        }
        return "";
    }

    @Override
    public ApiResponse getEnrichedDiscussionData(Map<String, Object> data, String token) {
        ApiResponse response = ProjectUtil.createDefaultResponse("discussion.getEnrichedDiscussionData");
        Map<String, Object> requestData = (Map<String, Object>) data.get("request");

        String userId = accessTokenValidator.verifyUserToken(token);
        if (StringUtils.isBlank(userId) || userId.equals(Constants.UNAUTHORIZED)) {
            response.getParams().setErrMsg(Constants.INVALID_AUTH_TOKEN);
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return response;
        }

        String errMsg = validateEnrichDataRequest(requestData);
        if (StringUtils.isNotBlank(errMsg)) {
            return ProjectUtil.returnErrorMsg(errMsg, HttpStatus.BAD_REQUEST, response, Constants.FAILED);
        }

        List<Map<String, Object>> communityFilters = (List<Map<String, Object>>) requestData.get(Constants.COMMUNITY_FILTERS);
        List<String> filters = (List<String>) requestData.get(Constants.FILTERS);

        List<String> allDiscussionIds = new ArrayList<>();
        for (Map<String, Object> communityFilter : communityFilters) {
            List<String> discussionIdsForCommunity = (List<String>) communityFilter.get("identifier");
            allDiscussionIds.addAll(discussionIdsForCommunity);
        }

        Map<String, Boolean> likesMap = initializeDefaultMap(allDiscussionIds, false);
        Map<String, Boolean> bookmarksMap = initializeDefaultMap(allDiscussionIds, false);
        Map<String, Boolean> reportedMap = initializeDefaultMap(allDiscussionIds, false);

        try {
            for (Map<String, Object> communityFilter : communityFilters) {
                String communityId = (String) communityFilter.get(Constants.COMMUNITY_ID);
                List<String> discussionIds = (List<String>) communityFilter.get(Constants.IDENTIFIER);

                if (filters.contains(Constants.LIKES)) {
                    fetchLikes(discussionIds, userId, likesMap);
                }
                if (filters.contains(Constants.BOOKMARKS)) {
                    fetchBookmarks(discussionIds, userId, communityId, bookmarksMap);
                }
                if (filters.contains(Constants.REPORTED)) {
                    fetchReported(discussionIds, userId, reportedMap);
                }
            }

            Map<String, Object> searchResults = new HashMap<>();
            searchResults.put(Constants.LIKES, likesMap);
            searchResults.put(Constants.BOOKMARKS, bookmarksMap);
            searchResults.put(Constants.REPORTED, reportedMap);
            response.setResult(Collections.singletonMap(Constants.SEARCH_RESULTS, searchResults));

        } catch (Exception e) {
            log.error("DiscussionService::getEnrichedDiscussionData: Failed to fetch discussions", e);
            return ProjectUtil.returnErrorMsg("getEnrichedDiscussionData", HttpStatus.INTERNAL_SERVER_ERROR, response, Constants.FAILED);
        }
        return response;
    }

    private Map<String, Boolean> initializeDefaultMap(List<String> discussionIds, boolean defaultValue) {
        Map<String, Boolean> defaultMap = new HashMap<>();
        for (String discussionId : discussionIds) {
            defaultMap.put(discussionId, defaultValue);
        }
        return defaultMap;
    }

    private void fetchLikes(List<String> discussionIds, String userId, Map<String, Boolean> likesMap) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(Constants.DISCUSSION_ID_KEY, discussionIds);
        properties.put(Constants.USERID, userId);

        List<Map<String, Object>> likesList = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                Constants.KEYSPACE_SUNBIRD, Constants.USER_POST_VOTES, properties, null, null);

        likesList.stream()
                .filter(record -> Boolean.TRUE.equals(record.get(Constants.VOTE_TYPE)))
                .map(record -> (String) record.get(Constants.DISCUSSION_ID_KEY))
                .forEach(discussionId -> likesMap.put(discussionId, true));
    }

    private void fetchBookmarks(List<String> discussionIds, String userId, String communityId, Map<String, Boolean> bookmarksMap) {

        Map<String, Object> properties = new HashMap<>();
        properties.put(Constants.DISCUSSION_ID_KEY, discussionIds);
        properties.put(Constants.USERID, userId);
        properties.put(Constants.COMMUNITY_ID, communityId);

        List<Map<String, Object>> bookmarksList = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                Constants.KEYSPACE_SUNBIRD, Constants.DISCUSSION_BOOKMARKS, properties, null, null);

        bookmarksList.stream()
                .filter(record -> Boolean.TRUE.equals(record.get(Constants.STATUS)))
                .map(record -> (String) record.get(Constants.DISCUSSION_ID_KEY))
                .forEach(discussionId -> bookmarksMap.put(discussionId, true));
    }

    private void fetchReported(List<String> discussionIds, String userId, Map<String, Boolean> reportedMap) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(Constants.DISCUSSION_ID_KEY, discussionIds);
        properties.put(Constants.USERID, userId);

        List<Map<String, Object>> reportedList = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                Constants.KEYSPACE_SUNBIRD, Constants.DISCUSSION_POST_REPORT_LOOKUP_BY_USER, properties, null, null);

        reportedList.stream()
                .map(record -> (String) record.get(Constants.DISCUSSION_ID_KEY))
                .forEach(discussionId -> reportedMap.put(discussionId, true));
    }

    private String validateEnrichDataRequest(Map<String, Object> requestData) {
        if (requestData == null) {
            return Constants.MISSING_REQUEST_DATA;
        }

        List<String> errList = new ArrayList<>();

        Object communityFiltersObj = requestData.get(Constants.COMMUNITY_FILTERS);
        if (!(communityFiltersObj instanceof List<?>)) {
            errList.add("Missing or invalid communityFilters.");
        } else {
            List<?> communityFilters = (List<?>) communityFiltersObj;
            if (communityFilters.isEmpty()) {
                errList.add("Empty communityFilters.");
            } else {
                for (Object obj : communityFilters) {
                    if (!(obj instanceof Map<?, ?>)) {
                        errList.add("Invalid communityFilters structure.");
                        continue;
                    }

                    Map<?, ?> communityFilter = (Map<?, ?>) obj;

                    String communityId = (String) communityFilter.get(Constants.COMMUNITY_ID);
                    List<?> identifiers = (List<?>) communityFilter.get(Constants.IDENTIFIER);

                    if (StringUtils.isBlank(communityId) || identifiers == null || identifiers.isEmpty()) {
                        errList.add("Invalid communityFilter: communityId or identifiers are missing/empty.");
                    }
                }
            }
        }

        List<String> validFilters = Arrays.asList(Constants.LIKES, Constants.BOOKMARKS, Constants.REPORTED);
        if (!requestData.containsKey(Constants.FILTERS) ||
                !(requestData.get(Constants.FILTERS) instanceof List)) {
            errList.add(Constants.FILTERS);
        } else {
            List<?> filtersList = (List<?>) requestData.get(Constants.FILTERS);
            if (filtersList.isEmpty() || filtersList.stream().noneMatch(validFilters::contains)) {
                errList.add(Constants.FILTERS);
            }
        }
        return errList.isEmpty() ? "" : "Failed Due To Missing or Invalid Params - " + errList + ".";
    }

    @Override
    public ApiResponse getGlobalFeed(SearchCriteria searchCriteria, String token, boolean isOverride) {
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.DISCUSSION_GET_GLOBAL_FEED_API);
        String userId = accessTokenValidator.verifyUserToken(token);

        if (StringUtils.isBlank(userId) || Constants.UNAUTHORIZED.equals(userId)) {
            return ProjectUtil.returnErrorMsg(Constants.INVALID_AUTH_TOKEN, HttpStatus.UNAUTHORIZED, response, Constants.FAILED);
        }
        return getGlobalFeedUsingUserId(searchCriteria, userId, isOverride);
    }

    private ApiResponse getGlobalFeedUsingUserId(SearchCriteria searchCriteria, String userId, boolean isOverride) {
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.DISCUSSION_GET_GLOBAL_FEED_API);

        if (StringUtils.isBlank(userId) || Constants.UNAUTHORIZED.equals(userId)) {
            return ProjectUtil.returnErrorMsg(Constants.INVALID_AUTH_TOKEN, HttpStatus.UNAUTHORIZED, response, Constants.FAILED);
        }

        populateCommunityIds(userId, searchCriteria);
        if (CollectionUtils.isEmpty((Set<String>) searchCriteria.getFilterCriteriaMap().get(Constants.COMMUNITY_ID))) {
            return ProjectUtil.returnErrorMsg(Constants.NO_COMMUNITY_FOUND, HttpStatus.OK, response, Constants.SUCCESS);
        }
        response = searchDiscussion(searchCriteria, isOverride);
        return response;
    }

    private void populateCommunityIds(String userId, SearchCriteria searchCriteria) {
        Map<String, Object> propertyMap = new HashMap<>();
        propertyMap.put(Constants.USERID, userId);
        List<Map<String, Object>> communitiesData = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                Constants.KEYSPACE_SUNBIRD, Constants.USER_COMMUNITY, propertyMap, Arrays.asList(Constants.COMMUNITY_ID_KEY, Constants.STATUS), null);
        if (!CollectionUtils.isEmpty(communitiesData)) {
            Set<String> communityIds = communitiesData.stream()
                    .filter(community -> (boolean) community.get(Constants.STATUS))
                    .map(community -> (String) community.get(Constants.COMMUNITY_ID_KEY))
                    .collect(Collectors.toSet());

            if (!CollectionUtils.isEmpty(communityIds)) {
                searchCriteria.getFilterCriteriaMap().put(Constants.COMMUNITY_ID, communityIds);
            }
        }
    }

    private boolean isTrendingPost(SearchCriteria searchCriteria) {
        try {
            SearchCriteria trendingCriteria = objectMapper.readValue(cbServerProperties.getFilterCriteriaTrendingFeed(), SearchCriteria.class);
            trendingCriteria.setPageNumber(searchCriteria.getPageNumber());
            return searchCriteria.equals(trendingCriteria);
        } catch (Exception e) {
            log.error("Error occurred while checking if the post is trending", e);
            return false;
        }
    }

    private void enhanceCommunityData(List<Map<String, Object>> discussions) {
        Set<String> communityIds = discussions.stream()
                .map(discussion -> (String) discussion.get(Constants.COMMUNITY_ID))
                .collect(Collectors.toSet());

        // Fetch community data from Redis
        List<Object> redisResults = fetchDataForKeys(
                communityIds.stream().map(id -> Constants.COMMUNITY_PREFIX + id).collect(Collectors.toList()), false
        );

        Map<String, String> communityDetailsMap = redisResults.stream()
                .map(community -> (Map<String, Object>) community)
                .collect(Collectors.toMap(
                        community -> community.get(Constants.COMMUNITY_ID).toString(),
                        community -> (String) community.get(Constants.COMMUNITY_NAME)
                ));

        // Identify missing communityIds
        List<String> missingCommunityIds = communityIds.stream()
                .filter(id -> !communityDetailsMap.containsKey(id))
                .collect(Collectors.toList());

        // Fetch missing community data from PostgreSQL
        if (!missingCommunityIds.isEmpty()) {
            List<Object> postgresResults = fetchCommunityFromPrimary(missingCommunityIds);
            for (Object community : postgresResults) {
                Map<String, Object> communityMap = (Map<String, Object>) community;
                communityDetailsMap.put(
                        communityMap.get(Constants.COMMUNITY_ID_KEY).toString(),
                        (String) communityMap.get(Constants.COMMUNITY_NAME)
                );
            }
        }

        // Enhance discussions with community data
        discussions.forEach(discussion -> {
            String communityId = (String) discussion.get(Constants.COMMUNITY_ID);
            if (communityDetailsMap.containsKey(communityId)) {
                discussion.put(Constants.COMMUNITY_NAME, communityDetailsMap.get(communityId));
            }
        });
    }

    private List<Object> fetchCommunityFromPrimary(List<String> communityIds) {
        log.info("Fetching community data from PostgreSQL");
        List<Object> communityList = new ArrayList<>();
        long startTime = System.currentTimeMillis();

        List<CommunityEntity> communityEntities = communityEngagementRepository.findAllById(communityIds);
        updateMetricsDbOperation(Constants.DISCUSSION_SEARCH, Constants.POSTGRES, Constants.READ, startTime);

        communityList = communityEntities.stream()
                .map(communityEntity -> {
                    Map<String, Object> communityMap = new HashMap<>();
                    communityMap.put(Constants.COMMUNITY_ID_KEY, communityEntity.getCommunityId());
                    communityMap.put(Constants.COMMUNITY_NAME, communityEntity.getData().get(Constants.COMMUNITY_NAME).asText());
                    return communityMap;
                })
                .collect(Collectors.toList());
        return communityList;
    }

    private List<String> getTrendingPosts() {
        SearchCriteria searchCriteria = new SearchCriteria();
        searchCriteria.setFilterCriteriaMap(new HashMap<>());
        searchCriteria.setRequestedFields(Arrays.asList(Constants.COMMUNITY_ID));
        searchCriteria.getFilterCriteriaMap().put(Constants.STATUS, Constants.ACTIVE);
        searchCriteria.getFilterCriteriaMap().put(IS_PROFANE, false);
        searchCriteria.setOrderBy(Constants.COUNT_OF_ANSWER_POST_COUNT);
        searchCriteria.setOrderDirection(Constants.DESC);
        searchCriteria.setPageNumber(0);
        searchCriteria.setPageSize(10);
        SearchResult result = null;
        List<String> communityIds = new ArrayList<>();
        try {
            result = esUtilService.searchDocuments(cbServerProperties.getCommunityEntity(), searchCriteria, cbServerProperties.getElasticCommunityJsonPath());

            if (CollectionUtils.isNotEmpty(result.getData())) {
                List<Map<String, Object>> communities = result.getData();
                for (Map<String, Object> community : communities) {
                    communityIds.add((String) community.get(Constants.COMMUNITY_ID));
                }
            }
        } catch (Exception e) {
            log.error("Error occurred while fetching trending communities", e);
        }
        return communityIds;
    }

    private SearchCriteria createDefaultSearchCriteria(String parentAnswerPostId,
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


}
