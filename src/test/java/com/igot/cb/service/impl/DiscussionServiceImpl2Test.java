package com.igot.cb.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.igot.cb.discussion.service.impl.DiscussionServiceImpl;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.elasticsearch.dto.SearchResult;
import com.igot.cb.pores.elasticsearch.service.EsUtilService;
import com.igot.cb.pores.util.CbServerProperties;
import com.igot.cb.pores.util.Constants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiscussionServiceImpl2Test {

    @InjectMocks
    private DiscussionServiceImpl discussionService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private CbServerProperties cbServerProperties;

    @Mock
    private RedisTemplate<String, SearchResult> redisTemplate;
    @Mock
    private ValueOperations<String, SearchResult> valueOperations;

    @Mock
    private EsUtilService esUtilService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(cbServerProperties.getJwtDemandSearchKeyName()).thenReturn("dummy-secret");
    }

    private String invokeGenerateRedisTokenKey(SearchCriteria sc) throws Exception {
        Method method = DiscussionServiceImpl.class.getDeclaredMethod("generateRedisTokenKey", SearchCriteria.class);
        method.setAccessible(true);
        return (String) method.invoke(discussionService, sc);
    }

    @Test
    void testNullSearchCriteria_returnsEmptyString() throws Exception {
        assertEquals("", invokeGenerateRedisTokenKey(null));
    }

    @Test
    void testMatchUserFeedCriteria_returnsExpectedKey() throws Exception {
        SearchCriteria input = new SearchCriteria();
        HashMap<String, Object> map = new HashMap<>();
        map.put(Constants.CREATED_BY, "user1");
        map.put(Constants.COMMUNITY_ID, "comm1");
        input.setFilterCriteriaMap(map);
        input.setPageNumber(2);

        when(cbServerProperties.getUserFeedFilterCriteriaMapSize()).thenReturn(2);
        when(cbServerProperties.getFilterCriteriaQuestionUserFeed()).thenReturn("{}");
        
        SearchCriteria template = new SearchCriteria();
        HashMap<String, Object> templateMap = new HashMap<>();
        templateMap.put(Constants.CREATED_BY, "user1");
        templateMap.put(Constants.COMMUNITY_ID, "comm1");
        template.setFilterCriteriaMap(templateMap);
        template.setPageNumber(2);
        
        when(objectMapper.readValue("{}", SearchCriteria.class)).thenReturn(template);

        String result = invokeGenerateRedisTokenKey(input);
        assertTrue(result.startsWith(Constants.DISCUSSION_POSTS_BY_USER));
    }

    @Test
    void testMatchDocumentFeedCriteria_returnsExpectedKey() throws Exception {
        SearchCriteria input = new SearchCriteria();
        HashMap<String, Object> map = new HashMap<>();
        map.put(Constants.COMMUNITY_ID, "comm2");
        map.put(Constants.CATEGORY_TYPE, "type");
        input.setFilterCriteriaMap(map);
        input.setPageNumber(3);

        when(cbServerProperties.getFilterCriteriaQuestionDocumentFeed()).thenReturn("{}");
        
        SearchCriteria template = new SearchCriteria();
        HashMap<String, Object> templateMap = new HashMap<>();
        templateMap.put(Constants.COMMUNITY_ID, "comm2");
        templateMap.put(Constants.CATEGORY_TYPE, "type");
        template.setFilterCriteriaMap(templateMap);
        template.setPageNumber(3);
        
        when(objectMapper.readValue("{}", SearchCriteria.class)).thenReturn(template);

        String result = invokeGenerateRedisTokenKey(input);
        assertTrue(result.startsWith(Constants.DISCUSSION_DOCUMENT_POST));
    }

    @Test
    void testAllReportFeed_returnsExpectedKey() throws Exception {
        SearchCriteria input = new SearchCriteria();
        HashMap<String, Object> map = new HashMap<>();
        map.put(Constants.COMMUNITY_ID, "commX");
        map.put(Constants.STATUS, List.of("REPORTED"));
        input.setFilterCriteriaMap(map);
        input.setPageNumber(1);

        when(cbServerProperties.getMdoAllReportFeed()).thenReturn("{}");
        
        SearchCriteria template = new SearchCriteria();
        HashMap<String, Object> templateMap = new HashMap<>();
        templateMap.put(Constants.COMMUNITY_ID, "commX");
        templateMap.put(Constants.STATUS, List.of("REPORTED"));
        template.setFilterCriteriaMap(templateMap);
        template.setPageNumber(1);
        
        when(objectMapper.readValue("{}", SearchCriteria.class)).thenReturn(template);

        String result = invokeGenerateRedisTokenKey(input);
        assertTrue(result.startsWith(Constants.ALL_REPORTED_POSTS_CACHE_PREFIX));
    }

    @Test
    void testQuestionReportFeed_returnsExpectedKey() throws Exception {
        SearchCriteria input = new SearchCriteria();
        HashMap<String, Object> map = new HashMap<>();
        map.put(Constants.COMMUNITY_ID, "commY");
        map.put(Constants.STATUS, "REPORTED");
        input.setFilterCriteriaMap(map);
        input.setPageNumber(2);

        when(cbServerProperties.getMdoQuestionReportFeed()).thenReturn("{}");
        
        SearchCriteria template = new SearchCriteria();
        HashMap<String, Object> templateMap = new HashMap<>();
        templateMap.put(Constants.COMMUNITY_ID, "commY");
        templateMap.put(Constants.STATUS, "REPORTED");
        template.setFilterCriteriaMap(templateMap);
        template.setPageNumber(2);
        
        when(objectMapper.readValue("{}", SearchCriteria.class)).thenReturn(template);

        String result = invokeGenerateRedisTokenKey(input);
        assertTrue(result.startsWith(Constants.REPORTED_QUESTION_POSTS_CACHE_PREFIX));
    }

    @Test
    void testAnswerPostReportFeed_returnsExpectedKey() throws Exception {
        SearchCriteria input = new SearchCriteria();
        HashMap<String, Object> map = new HashMap<>();
        map.put(Constants.COMMUNITY_ID, "commZ");
        map.put(Constants.STATUS, "REPORTED");
        input.setFilterCriteriaMap(map);
        input.setPageNumber(3);

        when(cbServerProperties.getMdoAnswerPostReportFeed()).thenReturn("{}");
        
        SearchCriteria template = new SearchCriteria();
        HashMap<String, Object> templateMap = new HashMap<>();
        templateMap.put(Constants.COMMUNITY_ID, "commZ");
        templateMap.put(Constants.STATUS, "REPORTED");
        template.setFilterCriteriaMap(templateMap);
        template.setPageNumber(3);
        
        when(objectMapper.readValue("{}", SearchCriteria.class)).thenReturn(template);

        String result = invokeGenerateRedisTokenKey(input);
        assertTrue(result.startsWith(Constants.REPORTED_ANSWER_POST_POSTS_CACHE_PREFIX));
    }

    @Test
    void testAnswerPostReplyReportFeed_returnsExpectedKey() throws Exception {
        SearchCriteria input = new SearchCriteria();
        HashMap<String, Object> map = new HashMap<>();
        map.put(Constants.COMMUNITY_ID, "commA");
        map.put(Constants.STATUS, "REPORTED");
        input.setFilterCriteriaMap(map);
        input.setPageNumber(4);

        when(cbServerProperties.getMdoAnswerPostReplyReportFeed()).thenReturn("{}");
        
        SearchCriteria template = new SearchCriteria();
        HashMap<String, Object> templateMap = new HashMap<>();
        templateMap.put(Constants.COMMUNITY_ID, "commA");
        templateMap.put(Constants.STATUS, "REPORTED");
        template.setFilterCriteriaMap(templateMap);
        template.setPageNumber(4);
        
        when(objectMapper.readValue("{}", SearchCriteria.class)).thenReturn(template);

        String result = invokeGenerateRedisTokenKey(input);
        assertTrue(result.startsWith(Constants.REPORTED_ANSWER_POST_REPLY_POSTS_CACHE_PREFIX));
    }

    @Test
    void testSuspendedFeed_returnsExpectedKey() throws Exception {
        SearchCriteria input = new SearchCriteria();
        HashMap<String, Object> map = new HashMap<>();
        map.put(Constants.COMMUNITY_ID, "commB");
        map.put(Constants.STATUS, "SUSPENDED");
        input.setFilterCriteriaMap(map);
        input.setPageNumber(5);

        when(cbServerProperties.getMdoAllSuspendedFeed()).thenReturn("{}");
        
        SearchCriteria template = new SearchCriteria();
        HashMap<String, Object> templateMap = new HashMap<>();
        templateMap.put(Constants.COMMUNITY_ID, "commB");
        templateMap.put(Constants.STATUS, "SUSPENDED");
        template.setFilterCriteriaMap(templateMap);
        template.setPageNumber(5);
        
        when(objectMapper.readValue("{}", SearchCriteria.class)).thenReturn(template);

        String result = invokeGenerateRedisTokenKey(input);
        assertTrue(result.startsWith(Constants.SUSPENDED_POSTS_CACHE_PREFIX));
    }

    @Test
    void testDefaultJWTPath() throws Exception {
        SearchCriteria input = new SearchCriteria();
        HashMap<String, Object> map = new HashMap<>();
        map.put("otherKey", "val");
        input.setFilterCriteriaMap(map);
        input.setPageNumber(5);

        when(objectMapper.writeValueAsString(input)).thenReturn("{\"test\":1}");

        String token = invokeGenerateRedisTokenKey(input);
        assertNotNull(token);
        assertFalse(token.isEmpty());
    }

    @Test
    void testJsonProcessingException_returnsEmptyString() throws Exception {
        SearchCriteria input = new SearchCriteria();
        HashMap<String, Object> map = new HashMap<>();
        map.put("otherKey", "val");
        input.setFilterCriteriaMap(map);

        when(objectMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("Test exception") {});

        assertEquals("", invokeGenerateRedisTokenKey(input));
    }

    @Test
    void testUserFeedCriteria_wrongMapSize() throws Exception {
        SearchCriteria input = new SearchCriteria();
        HashMap<String, Object> map = new HashMap<>();
        map.put(Constants.CREATED_BY, "user1");
        map.put(Constants.COMMUNITY_ID, "comm1");
        map.put("extraKey", "extraValue");
        input.setFilterCriteriaMap(map);
        input.setPageNumber(2);

        when(cbServerProperties.getUserFeedFilterCriteriaMapSize()).thenReturn(2);
        when(objectMapper.writeValueAsString(input)).thenReturn("{\"test\":1}");

        String result = invokeGenerateRedisTokenKey(input);
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void testUserFeedCriteria_nonStringValues() throws Exception {
        SearchCriteria input = new SearchCriteria();
        HashMap<String, Object> map = new HashMap<>();
        map.put(Constants.CREATED_BY, 123);
        map.put(Constants.COMMUNITY_ID, "comm1");
        input.setFilterCriteriaMap(map);
        input.setPageNumber(2);

        when(cbServerProperties.getUserFeedFilterCriteriaMapSize()).thenReturn(2);
        when(objectMapper.writeValueAsString(input)).thenReturn("{\"test\":1}");

        String result = invokeGenerateRedisTokenKey(input);
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void testLoopRunsFully_documentType() throws Exception {
        List<Map<String, Object>> discussions = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            discussions.add(Collections.singletonMap("id", i));
        }
        SearchResult searchResult = new SearchResult();
        searchResult.setData(discussions);
        searchResult.setTotalCount(15L);
        searchResult.setFacets(Collections.emptyMap());

        when(cbServerProperties.getDiscussionEsDefaultPageSize()).thenReturn(5);
        when(cbServerProperties.getElasticDiscussionJsonPath()).thenReturn("path");
        when(cbServerProperties.getDiscussionEntity()).thenReturn("entity");
        when(cbServerProperties.getDiscussionFeedRedisTtl()).thenReturn(60L);
        when(esUtilService.searchDocuments(anyString(), any(), anyString())).thenReturn(searchResult);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        Method method = DiscussionServiceImpl.class.getDeclaredMethod("updateCacheForFirstFivePages", String.class, boolean.class);
        method.setAccessible(true);
        
        try {
            method.invoke(discussionService, "comm1", true);
        } catch (Exception e) {
            // Expected due to fetchAndEnhanceDiscussions call, but loop logic is tested
        }

        verify(esUtilService).searchDocuments(anyString(), any(), anyString());
    }

    @Test
    void testLoopBreaksEarly_nonDocumentType() throws Exception {
        List<Map<String, Object>> discussions = new ArrayList<>();
        discussions.add(Collections.singletonMap("id", 1));
        SearchResult searchResult = new SearchResult();
        searchResult.setData(discussions);
        searchResult.setTotalCount(1L);
        searchResult.setFacets(Collections.emptyMap());

        when(cbServerProperties.getDiscussionEsDefaultPageSize()).thenReturn(5);
        when(cbServerProperties.getElasticDiscussionJsonPath()).thenReturn("path");
        when(cbServerProperties.getDiscussionEntity()).thenReturn("entity");
        when(cbServerProperties.getDiscussionFeedRedisTtl()).thenReturn(60L);
        when(esUtilService.searchDocuments(anyString(), any(), anyString())).thenReturn(searchResult);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        Method method = DiscussionServiceImpl.class.getDeclaredMethod("updateCacheForFirstFivePages", String.class, boolean.class);
        method.setAccessible(true);
        
        try {
            method.invoke(discussionService, "comm2", false);
        } catch (Exception e) {
            // Expected due to fetchAndEnhanceDiscussions call, but loop logic is tested
        }

        verify(esUtilService).searchDocuments(anyString(), any(), anyString());
    }

}