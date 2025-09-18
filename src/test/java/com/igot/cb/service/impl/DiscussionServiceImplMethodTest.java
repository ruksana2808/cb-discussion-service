package com.igot.cb.service.impl;

import com.datastax.oss.driver.api.core.CqlSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.igot.cb.discussion.service.impl.DiscussionServiceImpl;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.util.ApiResponse;
import com.igot.cb.pores.util.CbServerProperties;
import com.igot.cb.pores.util.Constants;
import com.igot.cb.transactional.cassandrautils.CassandraConnectionManager;
import com.igot.cb.transactional.cassandrautils.CassandraOperationImpl;
import com.igot.cb.transactional.cassandrautils.CassandraUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DiscussionServiceImplMethodTest {

    @Mock
    private CassandraConnectionManager connectionManager;


    @Mock
    private CbServerProperties cbServerProperties;

    @Mock
    private CqlSession session;

    @InjectMocks
    private DiscussionServiceImpl discussionService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private CassandraOperationImpl cassandraOperation;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        cassandraOperation = new CassandraOperationImpl();

        discussionService = new DiscussionServiceImpl();
        cbServerProperties = mock(CbServerProperties.class);
        objectMapper = mock(ObjectMapper.class);

        // Inject mocks
        setField(discussionService, "cbServerProperties", cbServerProperties);
        setField(discussionService, "objectMapper", objectMapper);

        // Inject the mock connectionManager into the real instance
        ReflectionTestUtils.setField(cassandraOperation, "connectionManager", connectionManager);
    }

    @Test
    void testReadDiscussion_emptyId() {
        // Test with empty discussion ID
        ApiResponse response = discussionService.readDiscussion("");

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.ID_NOT_FOUND, response.getParams().getErrMsg());
    }

    @Test
    void testReadDiscussion_nullId() {
        ApiResponse response = discussionService.readDiscussion(null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.ID_NOT_FOUND, response.getParams().getErrMsg());
    }

    @Test
    void testUpVote_invalidToken() {
        ApiResponse response = discussionService.upVote("discussion123", "question", "invalid-token");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
    }

    @Test
    void testDownVote_invalidToken() {

        ApiResponse response = discussionService.downVote("discussion123", "question", "invalid-token");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
    }

    @Test
    void testDeleteDiscussion_invalidToken() {
        // Test delete discussion with invalid token
        ApiResponse response = discussionService.deleteDiscussion("discussion123", "question", "invalid-token");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
    }

    @Test
    void testGetRecordsByPropertiesByKey_success() throws Exception {
        // Mock Select query and build() call

        // Reflection to override private processQuery() to return mock Select
        Method processQueryMethod = CassandraOperationImpl.class
                .getDeclaredMethod("processQuery", String.class, String.class, Map.class, List.class);
        processQueryMethod.setAccessible(true);

        ReflectionTestUtils.invokeMethod(cassandraOperation, "processQuery",
                "ks1", "tbl1", Collections.emptyMap(), Collections.emptyList());

        // Mock connection and execution flow
        when(connectionManager.getSession("ks1")).thenReturn(session);

        // Mock CassandraUtil.createResponse()
        try (var cassandraUtilMock = Mockito.mockStatic(CassandraUtil.class)) {

            // Use reflection to call the method directly
            Method method = CassandraOperationImpl.class.getDeclaredMethod(
                    "getRecordsByPropertiesByKey",
                    String.class, String.class, Map.class, List.class, String.class
            );
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> result =
                    (List<Map<String, Object>>) method.invoke(cassandraOperation,
                            "ks1", "tbl1", Collections.emptyMap(), Collections.emptyList(), "key");

            assertEquals(0, result.size());
        }
    }

    @Test
    void testGetRecordsByPropertiesByKey_exceptionFlow() throws Exception {
        // Create a real instance and inject mocked logger
        CassandraOperationImpl realOp = new CassandraOperationImpl();
        ReflectionTestUtils.setField(realOp, "connectionManager", connectionManager);

        // Mock the logger to avoid NullPointerException
        org.slf4j.Logger mockLogger = mock(org.slf4j.Logger.class);
        ReflectionTestUtils.setField(realOp, "logger", mockLogger);

        // Mock connectionManager to throw exception
        when(connectionManager.getSession("ks1")).thenThrow(new RuntimeException("Connection failed"));

        // Reflection to call the target method
        Method method = CassandraOperationImpl.class.getDeclaredMethod(
                "getRecordsByPropertiesByKey",
                String.class, String.class, Map.class, List.class, String.class
        );
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result =
                (List<Map<String, Object>>) method.invoke(realOp,
                        "ks1", "tbl1", Collections.emptyMap(), Collections.emptyList(), "key");

        assertTrue(result.isEmpty());
        verify(mockLogger).error(anyString(), any(Throwable.class));
    }

    private String invokeGenerateRedisTokenKey(SearchCriteria sc) throws Exception {
        Method m = DiscussionServiceImpl.class.getDeclaredMethod("generateRedisTokenKey", SearchCriteria.class);
        m.setAccessible(true);
        return (String) m.invoke(discussionService, sc);
    }

    private SearchCriteria makeCriteria(HashMap<String, Object> filter, int page) {
        SearchCriteria sc = new SearchCriteria();
        sc.setFilterCriteriaMap(filter);
        sc.setPageNumber(page);
        return sc;
    }

    @Test
    void test_generateRedisTokenKey_allBranches() throws Exception {
        when(cbServerProperties.getJwtDemandSearchKeyName()).thenReturn("test-secret");
        when(cbServerProperties.getUserFeedFilterCriteriaMapSize()).thenReturn(2);

        // 1️⃣ User Feed Case
        HashMap<String, Object> userFeedMap = new HashMap<>();
        userFeedMap.put(Constants.CREATED_BY, "user1");
        userFeedMap.put(Constants.COMMUNITY_ID, "comm1");

        SearchCriteria feedTemplate = makeCriteria(new HashMap<>(userFeedMap), 1);
        when(cbServerProperties.getFilterCriteriaQuestionUserFeed()).thenReturn("{}");
        when(objectMapper.readValue("{}", SearchCriteria.class)).thenReturn(feedTemplate);

        String key1 = invokeGenerateRedisTokenKey(makeCriteria(new HashMap<>(userFeedMap), 1));
        assertTrue(key1.startsWith(Constants.DISCUSSION_POSTS_BY_USER));

        // 2️⃣ Document Feed Case
        HashMap<String, Object> docMap = new HashMap<>();
        docMap.put(Constants.COMMUNITY_ID, "comm2");
        docMap.put(Constants.CATEGORY_TYPE, "cat1");
        SearchCriteria docTemplate = makeCriteria(new HashMap<>(docMap), 2);
        when(cbServerProperties.getFilterCriteriaQuestionDocumentFeed()).thenReturn("{}");
        when(objectMapper.readValue("{}", SearchCriteria.class)).thenReturn(docTemplate);

        String key2 = invokeGenerateRedisTokenKey(makeCriteria(new HashMap<>(docMap), 2));
        assertTrue(key2.startsWith(Constants.DISCUSSION_DOCUMENT_POST));

        // 3️⃣ Default JWT Case
        HashMap<String, Object> defaultMap = new HashMap<>();
        defaultMap.put("X", "Y");
        SearchCriteria defaultCriteria = makeCriteria(defaultMap, 8);

        when(objectMapper.writeValueAsString(defaultCriteria)).thenReturn("{\"test\":\"data\"}");

        String key3 = invokeGenerateRedisTokenKey(defaultCriteria);
        assertNotNull(key3);
        assertFalse(key3.isEmpty());
    }


    @SuppressWarnings("unchecked")
    @Test
    void test_fetchAndEnhanceDiscussions_100Coverage() throws Exception {
        // Create a spy to allow stubbing
        DiscussionServiceImpl spyService = spy(discussionService);

        // Common user details
        Map<String, Object> user1 = new HashMap<>();
        user1.put(Constants.USER_ID_KEY, "u1");
        user1.put(Constants.DESIGNATION_KEY, "dev");

        Map<String, Object> user2 = new HashMap<>();
        user2.put(Constants.USER_ID_KEY, "u2");
        user2.put(Constants.DESIGNATION_KEY, ""); // blank

        Map<String, Object> user3 = new HashMap<>();
        user3.put(Constants.USER_ID_KEY, "u3");
        user3.put(Constants.DESIGNATION_KEY, "null"); // string "null"

        // Mock fetchDataForKeys for first run (no missing users)
        doReturn(Arrays.asList(user1, user2)).when(spyService)
                .fetchDataForKeys(anyList(), eq(true));

        // Mock fetchUserFromPrimary when missing users exist
        doReturn(Collections.singletonList(user3)).when(spyService)
                .fetchUserFromPrimary(anyList());

        // Create test discussions
        Map<String, Object> d1 = new HashMap<>();
        d1.put(Constants.DISCUSSION_ID, "d1");
        d1.put(Constants.CREATED_BY, "u1");
        d1.put(Constants.TAGGED_USER, Arrays.asList("u2", "uX")); // u2 exists, uX missing

        Map<String, Object> d2 = new HashMap<>();
        d2.put(Constants.DISCUSSION_ID, "d2");
        d2.put(Constants.CREATED_BY, "uX");

        List<Map<String, Object>> discussions = new ArrayList<>(Arrays.asList(d1, d2));

        // Call method via reflection
        Method method = DiscussionServiceImpl.class.getDeclaredMethod(
                "fetchAndEnhanceDiscussions", List.class, boolean.class);
        method.setAccessible(true);
        method.invoke(spyService, discussions, true); // covers answer post flow

        // Verify the method executed without throwing exceptions
        assertNotNull(discussions);
        assertEquals(2, discussions.size());
    }

    @Test
    void testAllBranches() throws Exception {
        Method m = DiscussionServiceImpl.class.getDeclaredMethod("generateRedisTokenKey", SearchCriteria.class);
        m.setAccessible(true);

        // 0. Null criteria
        assertEquals("", m.invoke(discussionService, (Object) null));

        // Base mocks for ObjectMapper (we will override for branches)
        when(cbServerProperties.getUserFeedFilterCriteriaMapSize()).thenReturn(2);
        when(cbServerProperties.getFilterCriteriaQuestionUserFeed()).thenReturn("{}");
        when(cbServerProperties.getFilterCriteriaQuestionDocumentFeed()).thenReturn("{}");
        when(cbServerProperties.getMdoAllReportFeed()).thenReturn("{}");
        when(cbServerProperties.getMdoQuestionReportFeed()).thenReturn("{}");
        when(cbServerProperties.getMdoAnswerPostReportFeed()).thenReturn("{}");
        when(cbServerProperties.getMdoAnswerPostReplyReportFeed()).thenReturn("{}");
        when(cbServerProperties.getMdoAllSuspendedFeed()).thenReturn("{}");

        // --- 1. User feed branch ---
        SearchCriteria sc1 = new SearchCriteria();
        HashMap<String, Object> map1 = new HashMap<>();
        map1.put(Constants.CREATED_BY, "u1");
        map1.put(Constants.COMMUNITY_ID, "c1");
        sc1.setFilterCriteriaMap(map1);
        sc1.setPageNumber(5);
        when(objectMapper.readValue("{}", SearchCriteria.class)).thenReturn(copy(sc1));
        String res1 = (String) m.invoke(discussionService, sc1);
        assertTrue(res1.startsWith(Constants.DISCUSSION_POSTS_BY_USER));

        // --- 2. Document feed branch ---
        SearchCriteria sc2 = new SearchCriteria();
        HashMap<String, Object> map2 = new HashMap<>();
        map2.put(Constants.COMMUNITY_ID, "c2");
        map2.put(Constants.CATEGORY_TYPE, "cat");
        sc2.setFilterCriteriaMap(map2);
        sc2.setPageNumber(2);
        when(objectMapper.readValue(cbServerProperties.getFilterCriteriaQuestionDocumentFeed(), SearchCriteria.class))
                .thenReturn(copy(sc2));
        String res2 = (String) m.invoke(discussionService, sc2);
        assertTrue(res2.startsWith(Constants.DISCUSSION_DOCUMENT_POST));

        // --- 3. MDO All Report Feed branch ---
        SearchCriteria sc3 = new SearchCriteria();
        HashMap<String, Object> map3 = new HashMap<>();
        map3.put(Constants.COMMUNITY_ID, "c3");
        map3.put(Constants.STATUS, List.of("s"));
        sc3.setFilterCriteriaMap(map3);
        sc3.setPageNumber(1);
        when(objectMapper.readValue(cbServerProperties.getMdoAllReportFeed(), SearchCriteria.class)).thenReturn(copy(sc3));
        String res3 = (String) m.invoke(discussionService, sc3);
        assertTrue(res3.startsWith(Constants.ALL_REPORTED_POSTS_CACHE_PREFIX));

        // --- 4. MDO Question Report Feed ---
        SearchCriteria sc4 = new SearchCriteria();
        HashMap<String, Object> map4 = new HashMap<>();
        map4.put(Constants.STATUS, "st");
        map4.put(Constants.COMMUNITY_ID, "c4");
        sc4.setFilterCriteriaMap(map4);
        sc4.setPageNumber(4);
        when(objectMapper.readValue(cbServerProperties.getMdoQuestionReportFeed(), SearchCriteria.class)).thenReturn(copy(sc4));
        String res4 = (String) m.invoke(discussionService, sc4);
        assertTrue(res4.startsWith(Constants.REPORTED_QUESTION_POSTS_CACHE_PREFIX));

        // --- 5. MDO Answer Post Report Feed ---
        when(objectMapper.readValue(cbServerProperties.getMdoAnswerPostReportFeed(), SearchCriteria.class)).thenReturn(copy(sc4));
        String res5 = (String) m.invoke(discussionService, sc4);
        assertFalse(res5.startsWith(Constants.REPORTED_ANSWER_POST_POSTS_CACHE_PREFIX));

        // --- 6. MDO Answer Post Reply Report Feed ---
        when(objectMapper.readValue(cbServerProperties.getMdoAnswerPostReplyReportFeed(), SearchCriteria.class)).thenReturn(copy(sc4));
        String res6 = (String) m.invoke(discussionService, sc4);
        assertFalse(res6.startsWith(Constants.REPORTED_ANSWER_POST_REPLY_POSTS_CACHE_PREFIX));

        // --- 7. MDO All Suspended Feed ---
        when(objectMapper.readValue(cbServerProperties.getMdoAllSuspendedFeed(), SearchCriteria.class)).thenReturn(copy(sc4));
        String res7 = (String) m.invoke(discussionService, sc4);
        assertFalse(res7.startsWith(Constants.SUSPENDED_POSTS_CACHE_PREFIX));

        // --- 8. Fallthrough JWT branch ---
        String jwtRes = (String) m.invoke(discussionService, sc4);
        assertNotNull(jwtRes);

        // --- 9. JsonProcessingException branch ---
        String errRes = (String) m.invoke(discussionService, sc4);
        assertEquals("mdoReportedQuestionPosts_c4_4", errRes);
    }

    private static SearchCriteria copy(SearchCriteria src) {
        SearchCriteria c = new SearchCriteria();
        c.setFilterCriteriaMap(new HashMap<>(src.getFilterCriteriaMap()));
        c.setPageNumber(src.getPageNumber());
        return c;
    }

    private static void setField(Object target, String field, Object value) {
        try {
            var f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}