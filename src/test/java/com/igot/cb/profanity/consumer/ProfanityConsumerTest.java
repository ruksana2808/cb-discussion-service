package com.igot.cb.profanity.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.discussion.entity.DiscussionAnswerPostReplyEntity;
import com.igot.cb.discussion.entity.DiscussionEntity;
import com.igot.cb.discussion.repository.DiscussionAnswerPostReplyRepository;
import com.igot.cb.discussion.repository.DiscussionRepository;
import com.igot.cb.discussion.service.AnswerPostReplyService;
import com.igot.cb.discussion.service.DiscussionService;
import com.igot.cb.notificationUtill.HelperMethodService;
import com.igot.cb.notificationUtill.NotificationTriggerService;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.elasticsearch.service.EsUtilService;
import com.igot.cb.pores.util.CbServerProperties;
import com.igot.cb.pores.util.Constants;
import com.igot.cb.pores.util.DiscussionServiceUtil;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProfanityConsumerTest {

    @InjectMocks
    private ProfanityConsumer profanityConsumer;

    @Mock
    private ObjectMapper mapper;

    @Mock
    private DiscussionRepository discussionRepository;

    @Mock
    private DiscussionAnswerPostReplyRepository discussionAnswerPostReplyRepository;

    @Mock
    private EsUtilService esUtilService;

    @Mock
    private CbServerProperties cbServerProperties;

    @Mock
    private HelperMethodService helperMethodService;

    @Mock
    private NotificationTriggerService notificationTriggerService;

    @Mock
    private DiscussionService discussionService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOps;

    @Mock
    private AnswerPostReplyService answerPostReplyService;

    @Mock private DiscussionServiceUtil discussionServiceUtil;

    private static final String BASE_JSON_TEMPLATE = """
            {
                "request_data": {
                    "metadata": {
                        "post_id": "%s",
                        "type": "%s"
                    }
                },
                "response_data": {
                    "response": {
                        "isProfane": %s
                    }
                }
            }
            """;

    @BeforeEach
    void setUp() {
        Mockito.reset(discussionRepository, discussionAnswerPostReplyRepository, esUtilService);
    }

    private Object invokePrivate(String methodName, Class<?>[] paramTypes, Object... args) throws Exception {
        Method m = ProfanityConsumer.class.getDeclaredMethod(methodName, paramTypes);
        m.setAccessible(true);
        return m.invoke(profanityConsumer, args);
    }

    @Test
    void testCheckTextContentIsProfane_QuestionType() throws Exception {
        String kafkaValue = BASE_JSON_TEMPLATE.formatted("post123", Constants.QUESTION, true);
        ConsumerRecord<String, String> consumerRecord =
                new ConsumerRecord<>("topic", 0, 0L, null, kafkaValue);
        JsonNode mockNode = mockJsonTree("post123", Constants.QUESTION, true);
        when(mapper.readTree(kafkaValue)).thenReturn(mockNode);
        when(mockNode.toString()).thenReturn(kafkaValue);
        profanityConsumer.checkTextContentIsProfane(consumerRecord);
        await().atMost(1, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(discussionRepository).updateProfanityFieldsByDiscussionId("post123", kafkaValue, true,Constants.PROFANITY_CHECK_PASSED);
        });
    }

    @Test
    void testCheckTextContentIsProfane_AnswerPostType() throws Exception {
        String kafkaValue = BASE_JSON_TEMPLATE.formatted("post456", Constants.ANSWER_POST, false);
        ConsumerRecord<String, String> consumerRecord =
                new ConsumerRecord<>("topic", 0, 0L, null, kafkaValue);
        JsonNode mockNode = mockJsonTree("post456", Constants.ANSWER_POST, false);
        when(mapper.readTree(kafkaValue)).thenReturn(mockNode);
        when(mockNode.toString()).thenReturn(kafkaValue);
        profanityConsumer.checkTextContentIsProfane(consumerRecord);
        await().atMost(1, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(discussionRepository).updateProfanityFieldsByDiscussionId("post456", kafkaValue, false,Constants.PROFANITY_CHECK_PASSED);
        });
    }

    @Test
    void testCheckTextContentIsProfane_AnswerPostReplyType() throws Exception {
        String kafkaValue = BASE_JSON_TEMPLATE.formatted("reply123", Constants.ANSWER_POST_REPLY, true);
        ConsumerRecord<String, String> consumerRecord =
                new ConsumerRecord<>("topic", 0, 0L, null, kafkaValue);
        JsonNode mockNode = mockJsonTree("reply123", Constants.ANSWER_POST_REPLY, true);
        when(mapper.readTree(kafkaValue)).thenReturn(mockNode);
        when(mockNode.toString()).thenReturn(kafkaValue);
        profanityConsumer.checkTextContentIsProfane(consumerRecord);
        await().atMost(1, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(discussionAnswerPostReplyRepository)
                    .updateProfanityFieldsByDiscussionId("reply123", kafkaValue, true,Constants.PROFANITY_CHECK_PASSED);
        });
    }

    @Test
    void testCheckTextContentIsProfane_InvalidJson() throws Exception {
        ConsumerRecord<String, String> consumerRecord = new ConsumerRecord<>("topic", 0, 0L, null, "invalid_json");
        when(mapper.readTree("invalid_json")).thenThrow(new JsonProcessingException("error") {});
        profanityConsumer.checkTextContentIsProfane(consumerRecord);
        verifyNoInteractions(discussionRepository);
        verifyNoInteractions(discussionAnswerPostReplyRepository);
    }

    @Test
    void testCheckTextContentIsProfane_EmptyValue() {
        ConsumerRecord<String, String> consumerRecord = new ConsumerRecord<>("topic", 0, 0L, null, "");
        profanityConsumer.checkTextContentIsProfane(consumerRecord);
        verifyNoInteractions(discussionRepository);
        verifyNoInteractions(discussionAnswerPostReplyRepository);
    }

    @Test
    void testSyncProfaneDetailsToESForDiscussion_NotFound() throws Exception {
        String discussionId = "notfound";
        when(discussionRepository.findById(discussionId)).thenReturn(Optional.empty());
        invokePrivate("syncProfaneDetailsToESForDiscussion",
                new Class[]{String.class, boolean.class, String.class, String.class},
                discussionId, true, "question", null);
        verifyNoInteractions(esUtilService);
    }

    @Test
    void testSyncProfaneDetailsToESForDiscussion_Inactive() throws Exception {
        String discussionId = "inactive";
        DiscussionEntity entity = mock(DiscussionEntity.class);
        when(discussionRepository.findById(discussionId)).thenReturn(Optional.of(entity));
        when(entity.getIsActive()).thenReturn(false);
        invokePrivate("syncProfaneDetailsToESForDiscussion",
                new Class[]{String.class, boolean.class, String.class, String.class},
                discussionId, true, "question", null);

        verifyNoInteractions(esUtilService);
    }

    @Test
    void testSyncProfaneDetailsToESForDiscussion_Active_NotProfane() throws Exception {
        String discussionId = "activeNotProfane";
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("createdBy", "user123");
        data.put(Constants.COMMUNITY_ID, "community1");
        data.put(Constants.DISCUSSION_ID, discussionId);
        DiscussionEntity entity = mock(DiscussionEntity.class);
        when(discussionRepository.findById(discussionId)).thenReturn(Optional.of(entity));
        when(entity.getIsActive()).thenReturn(true);
        when(entity.getData()).thenReturn(data);
        when(entity.getDiscussionId()).thenReturn(discussionId);
        when(cbServerProperties.getDiscussionEntity()).thenReturn("discussionIndex");
        when(cbServerProperties.getElasticDiscussionJsonPath()).thenReturn("jsonPath");
        when(mapper.convertValue(eq(data), any(TypeReference.class))).thenReturn(new HashMap<>());
        Field objectMapperField = ProfanityConsumer.class.getDeclaredField("objectMapper");
        objectMapperField.setAccessible(true);
        objectMapperField.set(profanityConsumer, mapper);
        invokePrivate("syncProfaneDetailsToESForDiscussion",
                new Class[]{String.class, boolean.class, String.class, String.class},
                discussionId, false, "question", null);
        verify(esUtilService).updateDocument(eq("discussionIndex"), eq(discussionId), anyMap(), eq("jsonPath"));
    }



    @Test
    void testSyncProfaneDetailsToESForAnswerPost_NotFound() throws Exception {
        String discussionId = "notfound";
        when(discussionAnswerPostReplyRepository.findById(discussionId)).thenReturn(Optional.empty());
        invokePrivate("syncProfaneDetailsToESForAnswerPost",
                new Class[]{String.class, boolean.class, String.class, String.class},
                discussionId, true, "parentDisc", "parentAns");

        verifyNoInteractions(esUtilService);
    }

    @Test
    void testSyncProfaneDetailsToESForAnswerPost_Inactive() throws Exception {
        String discussionId = "inactive";
        DiscussionAnswerPostReplyEntity entity = mock(DiscussionAnswerPostReplyEntity.class);
        when(discussionAnswerPostReplyRepository.findById(discussionId)).thenReturn(Optional.of(entity));
        when(entity.getIsActive()).thenReturn(false);
        invokePrivate("syncProfaneDetailsToESForAnswerPost",
                new Class[]{String.class, boolean.class, String.class, String.class},
                discussionId, true, "parentDisc", "parentAns");
        verifyNoInteractions(esUtilService);
    }

    @Test
    void testSyncProfaneDetailsToESForAnswerPost_Active_NotProfane() throws Exception {
        String discussionId = "activeNotProfane";
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("createdBy", "user123");
        data.put(Constants.COMMUNITY_ID, "community1");
        data.put(Constants.DISCUSSION_ID, discussionId);
        DiscussionAnswerPostReplyEntity entity = mock(DiscussionAnswerPostReplyEntity.class);
        when(discussionAnswerPostReplyRepository.findById(discussionId)).thenReturn(Optional.of(entity));
        when(entity.getIsActive()).thenReturn(true);
        when(entity.getData()).thenReturn(data);
        when(entity.getDiscussionId()).thenReturn(discussionId);
        when(cbServerProperties.getDiscussionEntity()).thenReturn("answerIndex");
        when(cbServerProperties.getElasticDiscussionJsonPath()).thenReturn("jsonPath");
        when(mapper.convertValue(eq(data), any(TypeReference.class))).thenReturn(new HashMap<>());
        Field objectMapperField = ProfanityConsumer.class.getDeclaredField("objectMapper");
        objectMapperField.setAccessible(true);
        objectMapperField.set(profanityConsumer, mapper);
        invokePrivate("syncProfaneDetailsToESForAnswerPost",
                new Class[]{String.class, boolean.class, String.class, String.class},
                discussionId, false, "parentDisc", "parentAns");
        verify(esUtilService).updateDocument(eq("answerIndex"), eq(discussionId), anyMap(), eq("jsonPath"));
    }



    private JsonNode mockJsonTree(String postId, String type, boolean isProfane) {
        JsonNode mockRoot = mock(JsonNode.class);
        JsonNode requestData = mock(JsonNode.class);
        JsonNode metadata = mock(JsonNode.class);
        JsonNode postIdNode = mock(JsonNode.class);
        JsonNode typeNode = mock(JsonNode.class);
        JsonNode parentDiscussionNode = mock(JsonNode.class);
        JsonNode parentAnswerPostNode = mock(JsonNode.class);
        JsonNode responseData = mock(JsonNode.class);
        JsonNode responsePath = mock(JsonNode.class);
        JsonNode isProfaneNode = mock(JsonNode.class);
        when(mockRoot.path(Constants.REQUEST_DATA)).thenReturn(requestData);
        when(requestData.path(Constants.METADATA)).thenReturn(metadata);
        when(metadata.path(Constants.POST_ID)).thenReturn(postIdNode);
        when(metadata.path(Constants.TYPE)).thenReturn(typeNode);
        when(metadata.path(Constants.PARENT_DISCUSSION_ID)).thenReturn(parentDiscussionNode);
        when(metadata.path(Constants.PARENT_ANSWER_POST_ID)).thenReturn(parentAnswerPostNode);
        when(postIdNode.asText()).thenReturn(postId);
        when(typeNode.asText()).thenReturn(type);
        when(parentDiscussionNode.asText()).thenReturn("parent-disc");
        when(parentAnswerPostNode.asText()).thenReturn("parent-ans");
        when(mockRoot.path(Constants.RESPONSE_DATA)).thenReturn(responseData);
        when(responseData.path(Constants.RESPONSE_DATA_PATH)).thenReturn(responsePath);
        when(responsePath.path(Constants.IS_PROFANE)).thenReturn(isProfaneNode);
        when(isProfaneNode.asBoolean(false)).thenReturn(isProfane);
        return mockRoot;
    }

    @Test
    void testHandleProfanityForDiscussionAnswerPostCreation_WhenTypeIsQuestion() throws Exception {
        String type = Constants.QUESTION;
        String parentDiscussionId = "parent123";
        String userId = "user123";
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put(Constants.COMMUNITY_ID, "community1");
        data.put(Constants.DISCUSSION_ID, "disc123");
        data.put(Constants.DESCRIPTION, "Test question description");
        DiscussionEntity discussionEntity = mock(DiscussionEntity.class);
        ObjectNode entityData = JsonNodeFactory.instance.objectNode();
        entityData.put("createdBy", userId);
        when(discussionEntity.getData()).thenReturn(entityData);
        when(helperMethodService.fetchUserFirstName(userId)).thenReturn("TestUser");
        invokePrivate("handleProfanityForDiscussionAnswerPostCreation",
                new Class[]{String.class, String.class, DiscussionEntity.class, ObjectNode.class},
                type, parentDiscussionId, discussionEntity, data);
        verify(notificationTriggerService).triggerNotification(
                eq(Constants.PROFANITY_CHECK),
                eq(Constants.ALERT),
                eq(Collections.singletonList(userId)),
                eq("Test question description"),
                eq("TestUser"),
                argThat(map -> Boolean.TRUE.equals(map.get("isProfane")) &&
                        "community1".equals(map.get(Constants.COMMUNITY_ID)) &&
                        "disc123".equals(map.get(Constants.DISCUSSION_ID)))
        );
        verify(discussionService).deleteCacheByCommunity(Constants.DISCUSSION_CACHE_PREFIX + "community1");
        verify(discussionService).deleteCacheByCommunity(Constants.DISCUSSION_POSTS_BY_USER + "community1" + Constants.UNDER_SCORE + userId);
        verify(discussionService).updateCacheForFirstFivePages("community1", false);
    }

    @Test
    void testHandleProfanityForDiscussionAnswerPostCreation_WhenTypeIsAnswerPost() throws Exception {
        String type = Constants.ANSWER_POST;
        String parentDiscussionId = "parent123";
        String userId = "user123";
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put(Constants.COMMUNITY_ID, "community1");
        data.put(Constants.DISCUSSION_ID, "disc123");
        data.put(Constants.DESCRIPTION, "Test description");
        DiscussionEntity discussionEntity = mock(DiscussionEntity.class);
        ObjectNode entityData = JsonNodeFactory.instance.objectNode();
        entityData.put("createdBy", userId);
        when(discussionEntity.getData()).thenReturn(entityData);
        when(helperMethodService.fetchUserFirstName(userId)).thenReturn("TestUser");
        DiscussionEntity parentDiscussionEntity = mock(DiscussionEntity.class);
        when(discussionRepository.findById(parentDiscussionId)).thenReturn(Optional.of(parentDiscussionEntity));
        SearchCriteria mockCriteria = mock(SearchCriteria.class);
        when(discussionService.createSearchCriteriaWithDefaults(eq(parentDiscussionId), eq("community1"), eq(Constants.ANSWER_POST)))
                .thenReturn(mockCriteria);
        doReturn("dummy-key").when(discussionServiceUtil).generateRedisJwtTokenKey(any());
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.getAndDelete(anyString())).thenReturn("deleted");
        Field redisTemplateField = ProfanityConsumer.class.getDeclaredField("redisTemplate");
        redisTemplateField.setAccessible(true);
        redisTemplateField.set(profanityConsumer, redisTemplate);
        invokePrivate("handleProfanityForDiscussionAnswerPostCreation",
                new Class[]{String.class, String.class, DiscussionEntity.class, ObjectNode.class},
                type, parentDiscussionId, discussionEntity, data);
        verify(notificationTriggerService).triggerNotification(
                eq(Constants.PROFANITY_CHECK),
                eq(Constants.ALERT),
                eq(Collections.singletonList(userId)),
                eq("Test description"),
                eq("TestUser"),
                anyMap()
        );
        verify(discussionService).deleteCacheByCommunity(Constants.DISCUSSION_CACHE_PREFIX + "community1");
        verify(discussionService).updateCacheForFirstFivePages("community1", false);
        verify(redisTemplate.opsForValue()).getAndDelete(anyString());
        verify(discussionService).updateAnswerPostToDiscussion(eq(parentDiscussionEntity), eq("disc123"), eq(Constants.DECREMENT));
    }


    @Test
    void testHandleProfanityForAnswerPostReplyCreation() throws Exception {
        String parentDiscussionId = "parentDisc1";
        String parentAnswerPostId = "parentAns1";
        String userId = "user123";
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put(Constants.COMMUNITY_ID, "community1");
        data.put(Constants.DISCUSSION_ID, "reply123");
        DiscussionAnswerPostReplyEntity replyEntity = mock(DiscussionAnswerPostReplyEntity.class);
        ObjectNode entityData = JsonNodeFactory.instance.objectNode();
        entityData.put("createdBy", userId);
        when(replyEntity.getData()).thenReturn(entityData);
        when(helperMethodService.fetchUserFirstName(userId)).thenReturn("TestUser");
        SearchCriteria mockAnswerReplyCriteria = mock(SearchCriteria.class);
        SearchCriteria mockDiscussionCriteria = mock(SearchCriteria.class);
        when(answerPostReplyService.createDefaultSearchCriteria(eq(parentAnswerPostId), eq("community1")))
                .thenReturn(mockAnswerReplyCriteria);
        when(discussionService.createSearchCriteriaWithDefaults(eq(parentDiscussionId), eq("community1"), eq(Constants.ANSWER_POST)))
                .thenReturn(mockDiscussionCriteria);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        doReturn("dummy-key").when(discussionServiceUtil).generateRedisJwtTokenKey(any());
        when(valueOps.getAndDelete(anyString())).thenReturn("deleted");
        Field redisTemplateField = ProfanityConsumer.class.getDeclaredField("redisTemplate");
        redisTemplateField.setAccessible(true);
        redisTemplateField.set(profanityConsumer, redisTemplate);
        invokePrivate("handleProfanityForAnswerPostReplyCreation",
                new Class[]{String.class, String.class, DiscussionAnswerPostReplyEntity.class, ObjectNode.class},
                parentDiscussionId, parentAnswerPostId, replyEntity, data);
        verify(notificationTriggerService).triggerNotification(
                eq(Constants.PROFANITY_CHECK),
                eq(Constants.ALERT),
                eq(Collections.singletonList(userId)),
                eq(Constants.TITLE),
                eq("TestUser"),
                argThat(map -> Boolean.TRUE.equals(map.get(Constants.IS_PROFANE))
                        && "community1".equals(map.get(Constants.COMMUNITY_ID))
                        && "reply123".equals(map.get(Constants.DISCUSSION_ID)))
        );
        verify(redisTemplate.opsForValue(), times(2)).getAndDelete(anyString());
    }

    @Test
    void testSyncProfaneDetailsToESForDiscussion_Active_Profane() throws Exception {
        String discussionId = "activeProfane";
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("createdBy", "user123");
        data.put(Constants.COMMUNITY_ID, "community1");
        data.put(Constants.DISCUSSION_ID, discussionId);
        data.put(Constants.DESCRIPTION, "Test question description");
        DiscussionEntity entity = mock(DiscussionEntity.class);
        when(discussionRepository.findById(discussionId)).thenReturn(Optional.of(entity));
        when(entity.getIsActive()).thenReturn(true);
        when(entity.getData()).thenReturn(data);
        when(entity.getDiscussionId()).thenReturn(discussionId);
        when(cbServerProperties.getDiscussionEntity()).thenReturn("discussionIndex");
        when(cbServerProperties.getElasticDiscussionJsonPath()).thenReturn("jsonPath");
        when(mapper.convertValue(eq(data), any(TypeReference.class))).thenReturn(new HashMap<>());
        when(helperMethodService.fetchUserFirstName("user123")).thenReturn("John");
        Field objectMapperField = ProfanityConsumer.class.getDeclaredField("objectMapper");
        objectMapperField.setAccessible(true);
        objectMapperField.set(profanityConsumer, mapper);
        invokePrivate("syncProfaneDetailsToESForDiscussion",
                new Class[]{String.class, boolean.class, String.class, String.class},
                discussionId, true, Constants.QUESTION, null);
        verify(esUtilService).updateDocument(eq("discussionIndex"), eq(discussionId), anyMap(), eq("jsonPath"));
        verify(notificationTriggerService).triggerNotification(
                eq(Constants.PROFANITY_CHECK),
                eq(Constants.ALERT),
                eq(Collections.singletonList("user123")),
                eq("Test question description"),
                eq("John"),
                anyMap()
        );
        verify(discussionService).deleteCacheByCommunity(Constants.DISCUSSION_CACHE_PREFIX + "community1");
        verify(discussionService).deleteCacheByCommunity(Constants.DISCUSSION_POSTS_BY_USER + "community1" + "_" + "user123");
        verify(discussionService).updateCacheForFirstFivePages("community1", false);
    }



    @Test
    void testCheckTextContentIsProfane_UnknownType() throws Exception {
        String kafkaValue = BASE_JSON_TEMPLATE.formatted("post789", "UNKNOWN", true);
        ConsumerRecord<String, String> consumerRecord =
                new ConsumerRecord<>("topic", 0, 0L, null, kafkaValue);
        JsonNode mockNode = mockJsonTree("post789", "UNKNOWN", true);
        when(mapper.readTree(kafkaValue)).thenReturn(mockNode);
        profanityConsumer.checkTextContentIsProfane(consumerRecord);
        verifyNoInteractions(discussionRepository);
        verifyNoInteractions(discussionAnswerPostReplyRepository);
    }


    @Test
    void testCheckTextContentIsProfane_EmptyMessage() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("topic", 0, 0, "key", "");
        profanityConsumer.checkTextContentIsProfane(record);
    }

    @Test
    void testSyncProfaneDetailsToESForDiscussion_InactiveEntity() throws Exception {
        DiscussionEntity entity = mock(DiscussionEntity.class);
        when(discussionRepository.findById("discX")).thenReturn(Optional.of(entity));
        when(entity.getIsActive()).thenReturn(false);

        invokePrivate("syncProfaneDetailsToESForDiscussion",
                new Class[]{String.class, boolean.class, String.class, String.class},
                "discX", true, Constants.QUESTION, "parentX");

        verifyNoInteractions(esUtilService);
    }


    @Test
    void testSyncProfaneDetailsToESForAnswerPost_InactiveEntity() throws Exception {
        DiscussionAnswerPostReplyEntity entity = mock(DiscussionAnswerPostReplyEntity.class);
        when(discussionAnswerPostReplyRepository.findById("replyX")).thenReturn(Optional.of(entity));
        when(entity.getIsActive()).thenReturn(false);

        invokePrivate("syncProfaneDetailsToESForAnswerPost",
                new Class[]{String.class, boolean.class, String.class, String.class},
                "replyX", true, "parentDisc", "parentAns");

        verifyNoInteractions(esUtilService);
    }

    @Test
    void testUpdateProfanityFieldsAndSync_UnsupportedType() throws Exception {
        invokePrivate("updateProfanityFieldsAndSync",
                new Class[]{String.class, String.class, String.class, boolean.class, String.class, String.class},
                "UNKNOWN_TYPE", "disc123", "{}", false, null, null);
        verifyNoInteractions(discussionRepository);
        verifyNoInteractions(discussionAnswerPostReplyRepository);
    }

    @Test
    void testExtractFieldAsText_MissingNode() throws Exception {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        String value = (String) invokePrivate("extractFieldAsText", new Class[]{JsonNode.class, String[].class}, root, new String[]{"missing"});
        assertNull(value);
    }

    @Test
    void testCheckTextContentIsProfane_UpdateProfanityFieldsThrowsExceptionForQuestion() throws Exception {
        String kafkaValue = BASE_JSON_TEMPLATE.formatted("q123", Constants.QUESTION, true);
        ConsumerRecord<String, String> consumerRecord =
                new ConsumerRecord<>("topic", 0, 0L, null, kafkaValue);
        JsonNode mockNode = mockJsonTree("q123", Constants.QUESTION, true);
        when(mapper.readTree(kafkaValue)).thenReturn(mockNode);
        when(mockNode.toString()).thenReturn(kafkaValue);

        doThrow(new RuntimeException("DB error"))
                .when(discussionRepository).updateProfanityFieldsByDiscussionId(anyString(), anyString(), anyBoolean(), anyString());

        profanityConsumer.checkTextContentIsProfane(consumerRecord);

        await().atMost(1, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(discussionRepository).updateProfanityCheckStatusByDiscussionId("q123",
                    Constants.PROFANITY_CHECK_UPDATE_FAILED, false);
        });
    }

    @Test
    void testCheckTextContentIsProfane_UpdateProfanityFieldsThrowsExceptionForReply() throws Exception {
        String kafkaValue = BASE_JSON_TEMPLATE.formatted("r123", Constants.ANSWER_POST_REPLY, false);
        ConsumerRecord<String, String> consumerRecord =
                new ConsumerRecord<>("topic", 0, 0L, null, kafkaValue);
        JsonNode mockNode = mockJsonTree("r123", Constants.ANSWER_POST_REPLY, false);
        when(mapper.readTree(kafkaValue)).thenReturn(mockNode);
        when(mockNode.toString()).thenReturn(kafkaValue);

        doThrow(new RuntimeException("DB error"))
                .when(discussionAnswerPostReplyRepository).updateProfanityFieldsByDiscussionId(anyString(), anyString(), anyBoolean(), anyString());

        profanityConsumer.checkTextContentIsProfane(consumerRecord);

        await().atMost(1, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(discussionAnswerPostReplyRepository).updateProfanityCheckStatusByDiscussionId("r123",
                    Constants.PROFANITY_CHECK_UPDATE_FAILED, false);
        });
    }

    @Test
    void testSyncProfaneDetailsToESForAnswerPost_Active_Profane() throws Exception {
        String discussionId = "replyProfane";
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("createdBy", "u123");
        data.put(Constants.COMMUNITY_ID, "commX");
        data.put(Constants.DISCUSSION_ID, discussionId);

        DiscussionAnswerPostReplyEntity replyEntity = mock(DiscussionAnswerPostReplyEntity.class);
        when(discussionAnswerPostReplyRepository.findById(discussionId)).thenReturn(Optional.of(replyEntity));
        when(replyEntity.getIsActive()).thenReturn(true);
        when(replyEntity.getData()).thenReturn(data);
        when(replyEntity.getDiscussionId()).thenReturn(discussionId);

        // Mock DiscussionEntity for the parentAnswerPostId parameter
        DiscussionEntity discussionEntity = mock(DiscussionEntity.class);
        when(discussionRepository.findById("parentAnsX")).thenReturn(Optional.of(discussionEntity));

        when(cbServerProperties.getDiscussionEntity()).thenReturn("answerIndex");
        when(cbServerProperties.getElasticDiscussionJsonPath()).thenReturn("jsonPath");
        when(mapper.convertValue(eq(data), any(TypeReference.class))).thenReturn(new HashMap<>());

        // Inject objectMapper mock
        Field objectMapperField = ProfanityConsumer.class.getDeclaredField("objectMapper");
        objectMapperField.setAccessible(true);
        objectMapperField.set(profanityConsumer, mapper);

        // Inject redisTemplate mock
        Field redisTemplateField = ProfanityConsumer.class.getDeclaredField("redisTemplate");
        redisTemplateField.setAccessible(true);
        redisTemplateField.set(profanityConsumer, redisTemplate);
        doReturn("dummy-key").when(discussionServiceUtil).generateRedisJwtTokenKey(any());
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.getAndDelete(anyString())).thenReturn("deleted");

        invokePrivate("syncProfaneDetailsToESForAnswerPost",
                new Class[]{String.class, boolean.class, String.class, String.class},
                discussionId, true, "parentDiscX", "parentAnsX");

        verify(esUtilService).updateDocument(eq("answerIndex"), eq(discussionId), anyMap(), eq("jsonPath"));
        verify(answerPostReplyService).updateAnswerPostReplyToAnswerPost(eq(discussionEntity), eq(discussionId), eq(Constants.DECREMENT));
    }

    @Test
    void testExtractFieldAsText_WithNullNode() throws Exception {
        String result = (String) invokePrivate("extractFieldAsText",
                new Class[]{JsonNode.class, String[].class},
                (JsonNode) null, new String[]{"any"});
        assertNull(result);
    }

}
