package com.igot.cb.pores.elasticsearch.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.igot.cb.pores.elasticsearch.config.EsConfig;
import com.igot.cb.pores.elasticsearch.dto.FacetDTO;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.elasticsearch.dto.SearchResult;
import com.igot.cb.pores.exceptions.CustomException;
import com.igot.cb.pores.util.CbServerProperties;
import com.igot.cb.pores.util.Constants;
import com.networknt.schema.JsonSchemaFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.index.query.*;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.xcontent.XContentType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.Map.Entry;

@Service
@Slf4j
public class EsUtilServiceImpl implements EsUtilService {

    /*@Autowired
    private RestHighLevelClient elasticsearchClient;*/
    private final EsConfig esConfig;
    private final RestHighLevelClient elasticsearchClient;
    private final Logger logger = LogManager.getLogger(getClass());

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CbServerProperties cbServerProperties;

    @Autowired
    public EsUtilServiceImpl(RestHighLevelClient elasticsearchClient, EsConfig esConnection) {
        this.elasticsearchClient = elasticsearchClient;
        this.esConfig = esConnection;
    }


    @Override
    public RestStatus addDocument(
            String esIndexName, String type, String id, Map<String, Object> document, String JsonFilePath) {
        logger.info("EsUtilServiceImpl :: addDocument");
        try {
            JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance();
            InputStream schemaStream = schemaFactory.getClass().getResourceAsStream(JsonFilePath);
            Map<String, Object> map = objectMapper.readValue(schemaStream,
                    new TypeReference<Map<String, Object>>() {
                    });
            Iterator<Entry<String, Object>> iterator = document.entrySet().iterator();
            while (iterator.hasNext()) {
                Entry<String, Object> entry = iterator.next();
                String key = entry.getKey();
                if (!map.containsKey(key)) {
                    iterator.remove();
                }
            }
            IndexRequest indexRequest =
                    new IndexRequest(esIndexName, type, id).source(document, XContentType.JSON).setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);
            IndexResponse response = elasticsearchClient.index(indexRequest, RequestOptions.DEFAULT);
            logger.info("EsUtilServiceImpl :: addDocument :Insertion response {}", response.status());
            return response.status();
        } catch (Exception e) {
            logger.error("Issue while Indexing to es: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public RestStatus updateDocument(
            String index, String indexType, String entityId, Map<String, Object> updatedDocument, String JsonFilePath) {
        try {
            JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance();
            InputStream schemaStream = schemaFactory.getClass().getResourceAsStream(JsonFilePath);
            Map<String, Object> map = objectMapper.readValue(schemaStream,
                    new TypeReference<Map<String, Object>>() {
                    });
            Iterator<Entry<String, Object>> iterator = updatedDocument.entrySet().iterator();
            while (iterator.hasNext()) {
                Entry<String, Object> entry = iterator.next();
                String key = entry.getKey();
                if (!map.containsKey(key)) {
                    iterator.remove();
                }
            }
            IndexRequest indexRequest =
                    new IndexRequest(index)
                            .id(entityId)
                            .source(updatedDocument)
                            .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);
            IndexResponse response = elasticsearchClient.index(indexRequest, RequestOptions.DEFAULT);
            return response.status();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void deleteDocument(String documentId, String esIndexName) {
        try {
            DeleteRequest request = new DeleteRequest(esIndexName, Constants.INDEX_TYPE, documentId);
            DeleteResponse response = elasticsearchClient.delete(request, RequestOptions.DEFAULT);
            if (response.getResult() == DocWriteResponse.Result.DELETED) {
                logger.info("Document deleted successfully from elasticsearch.");
            } else {
                logger.error("Document not found or failed to delete from elasticsearch.");
            }
        } catch (Exception e) {
            logger.error("Error occurred during deleting document in elasticsearch");
        }
    }

    @Override
    public SearchResult searchDocuments(String esIndexName, SearchCriteria searchCriteria) {
        String searchString = searchCriteria.getSearchString();
        if (searchString != null && searchString.length() > cbServerProperties.getSearchStringMaxRegexLength()) {
            throw new RuntimeException("The length of the search string exceeds the allowed maximum of " + cbServerProperties.getSearchStringMaxRegexLength() + " characters.");
        }
        SearchSourceBuilder searchSourceBuilder = buildSearchSourceBuilder(searchCriteria);
        SearchRequest searchRequest = new SearchRequest(esIndexName);
        searchRequest.source(searchSourceBuilder);
        try {
            if (searchSourceBuilder != null) {
                int pageNumber = searchCriteria.getPageNumber();
                int pageSize = searchCriteria.getPageSize();
                int from = pageNumber * pageSize;
                searchSourceBuilder.from(from);
                if (pageSize != 0) {
                    searchSourceBuilder.size(pageSize);
                }
            }
            SearchResponse paginatedSearchResponse =
                    elasticsearchClient.search(searchRequest, RequestOptions.DEFAULT);
            List<Map<String, Object>> paginatedResult = extractPaginatedResult(paginatedSearchResponse);
            Map<String, List<FacetDTO>> fieldAggregations =
                    extractFacetData(paginatedSearchResponse, searchCriteria);
            SearchResult searchResult = new SearchResult();
            searchResult.setData(objectMapper.valueToTree(paginatedResult));
            searchResult.setFacets(fieldAggregations);
            searchResult.setTotalCount(paginatedSearchResponse.getHits().getTotalHits().value);
            return searchResult;
        } catch (IOException e) {
            logger.error("Error while fetching details from elastic search");
            return null;
        }
    }

    private Map<String, List<FacetDTO>> extractFacetData(
            SearchResponse searchResponse, SearchCriteria searchCriteria) {
        Map<String, List<FacetDTO>> fieldAggregations = new HashMap<>();
        if (searchCriteria.getFacets() != null) {
            for (String field : searchCriteria.getFacets()) {
                Terms fieldAggregation = searchResponse.getAggregations().get(field + "_agg");
                List<FacetDTO> fieldValueList = new ArrayList<>();
                for (Terms.Bucket bucket : fieldAggregation.getBuckets()) {
                    if (!bucket.getKeyAsString().isEmpty()) {
                        FacetDTO facetDTO = new FacetDTO(bucket.getKeyAsString(), bucket.getDocCount());
                        fieldValueList.add(facetDTO);
                    }
                }
                fieldAggregations.put(field, fieldValueList);
            }
        }
        return fieldAggregations;
    }

    private List<Map<String, Object>> extractPaginatedResult(SearchResponse paginatedSearchResponse) {
        SearchHit[] hits = paginatedSearchResponse.getHits().getHits();
        List<Map<String, Object>> paginatedResult = new ArrayList<>();
        for (SearchHit hit : hits) {
            paginatedResult.add(hit.getSourceAsMap());
        }
        return paginatedResult;
    }

    private SearchSourceBuilder buildSearchSourceBuilder(SearchCriteria searchCriteria) {
        logger.info("Building search query");
        if (searchCriteria == null || searchCriteria.toString().isEmpty()) {
            logger.error("Search criteria body is missing");
            return null;
        }
        BoolQueryBuilder boolQueryBuilder = buildFilterQuery(searchCriteria.getFilterCriteriaMap());
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.query(boolQueryBuilder);
        addSortToSearchSourceBuilder(searchCriteria, searchSourceBuilder);
        addRequestedFieldsToSearchSourceBuilder(searchCriteria, searchSourceBuilder);
       // addQueryStringToFilter(searchCriteria.getSearchString(), boolQueryBuilder);
        String searchString = searchCriteria.getSearchString();
        if (isNotBlank(searchString)) {
            QueryBuilder matchPhraseQuery = getMatchPhraseQuery("searchTags.keyword", searchString, true,boolQueryBuilder);
            boolQueryBuilder.must(matchPhraseQuery);
        }
        addFacetsToSearchSourceBuilder(searchCriteria.getFacets(), searchSourceBuilder);
        QueryBuilder queryPart = buildQueryPart(searchCriteria.getQuery());
        boolQueryBuilder.must(queryPart);
        logger.info("final search query result {}", searchSourceBuilder);
        return searchSourceBuilder;
    }

    private BoolQueryBuilder buildFilterQuery(Map<String, Object> filterCriteriaMap) {
        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
        List<Map<String, Object>> mustNotConditions = new ArrayList<>();

        if (filterCriteriaMap != null) {
            filterCriteriaMap.forEach(
                    (field, value) -> {
                        if (field.equals("must_not") && value instanceof ArrayList) {
                            mustNotConditions.addAll((List<Map<String, Object>>) value);
                        } else if (value instanceof Boolean) {
                            boolQueryBuilder.must(QueryBuilders.termQuery(field, value));
                        } else if (value instanceof ArrayList) {
                            boolQueryBuilder.must(
                                    QueryBuilders.termsQuery(
                                            field + Constants.KEYWORD, ((ArrayList<?>) value).toArray()));
                        } else if (value instanceof String) {
                            boolQueryBuilder.must(QueryBuilders.termsQuery(field + Constants.KEYWORD, value));
                        } else if (value instanceof Map) {
                            Map<String, Object> nestedMap = (Map<String, Object>) value;
                            if (isRangeQuery(nestedMap)) {
                                // Handle range query
                                BoolQueryBuilder rangeOrNullQuery = QueryBuilders.boolQuery();
                                RangeQueryBuilder rangeQuery = QueryBuilders.rangeQuery(field);
                                nestedMap.forEach((rangeOperator, rangeValue) -> {
                                    switch (rangeOperator) {
                                        case Constants.SEARCH_OPERATION_GREATER_THAN_EQUALS:
                                            rangeQuery.gte(rangeValue);
                                            break;
                                        case Constants.SEARCH_OPERATION_LESS_THAN_EQUALS:
                                            rangeQuery.lte(rangeValue);
                                            break;
                                        case Constants.SEARCH_OPERATION_GREATER_THAN:
                                            rangeQuery.gt(rangeValue);
                                            break;
                                        case Constants.SEARCH_OPERATION_LESS_THAN:
                                            rangeQuery.lt(rangeValue);
                                            break;
                                    }
                                });
                                rangeOrNullQuery.should(rangeQuery);
                                rangeOrNullQuery.should(QueryBuilders.boolQuery().mustNot(QueryBuilders.existsQuery(field)));
                                boolQueryBuilder.must(rangeOrNullQuery);
                            } else {
                                nestedMap.forEach((nestedField, nestedValue) -> {
                                    String fullPath = field + "." + nestedField;
                                    if (nestedValue instanceof Boolean) {
                                        boolQueryBuilder.must(QueryBuilders.termQuery(fullPath, nestedValue));
                                    } else if (nestedValue instanceof String) {
                                        boolQueryBuilder.must(QueryBuilders.termQuery(fullPath + Constants.KEYWORD, nestedValue));
                                    } else if (nestedValue instanceof ArrayList) {
                                        boolQueryBuilder.must(
                                                QueryBuilders.termsQuery(
                                                        fullPath + Constants.KEYWORD, ((ArrayList<?>) nestedValue).toArray()));
                                    }
                                });
                            }
                        }
                    });
            if (mustNotConditions != null) {
                mustNotConditions.forEach(condition -> {
                    boolQueryBuilder.mustNot(buildQueryPart(condition));
                });
            }
        }
        return boolQueryBuilder;
    }

    private void addSortToSearchSourceBuilder(
            SearchCriteria searchCriteria, SearchSourceBuilder searchSourceBuilder) {
        if (isNotBlank(searchCriteria.getOrderBy()) && isNotBlank(searchCriteria.getOrderDirection())) {
            SortOrder sortOrder =
                    Constants.ASC.equals(searchCriteria.getOrderDirection()) ? SortOrder.ASC : SortOrder.DESC;
            searchSourceBuilder.sort(
                    SortBuilders.fieldSort(searchCriteria.getOrderBy() + Constants.KEYWORD).order(sortOrder));
        }
    }

    private void addRequestedFieldsToSearchSourceBuilder(
            SearchCriteria searchCriteria, SearchSourceBuilder searchSourceBuilder) {
        if (searchCriteria.getRequestedFields() == null) {
            // Get all fields in response
            searchSourceBuilder.fetchSource(null);
        } else {
            if (searchCriteria.getRequestedFields().isEmpty()) {
                logger.error("Please specify at least one field to include in the results.");
            }
            searchSourceBuilder.fetchSource(
                    searchCriteria.getRequestedFields().toArray(new String[0]), null);
        }
    }

    private void addQueryStringToFilter(String searchString, BoolQueryBuilder boolQueryBuilder) {
        if (isNotBlank(searchString)) {
            boolQueryBuilder.must(
                    QueryBuilders.boolQuery()
                            .should(new WildcardQueryBuilder("searchTags.keyword", "*" + searchString.toLowerCase() + "*")));
        }
    }

    private QueryBuilder getMatchPhraseQuery(String propertyName, String values, boolean match,BoolQueryBuilder boolQueryBuilder) {
        BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery();
        if (match) {
                queryBuilder.should(QueryBuilders
                        .regexpQuery(propertyName,
                                ".*" + values.toLowerCase() + ".*"));
            } else {
                queryBuilder.mustNot(QueryBuilders
                        .regexpQuery(propertyName,
                                ".*" + values.toLowerCase() + ".*"));
            }

        return queryBuilder;
    }

    private void addFacetsToSearchSourceBuilder(
            List<String> facets, SearchSourceBuilder searchSourceBuilder) {
        if (facets != null) {
            for (String field : facets) {
                searchSourceBuilder.aggregation(
                        AggregationBuilders.terms(field + "_agg").field(field + ".keyword").size(250));
            }
        }
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    @Override
    public void deleteDocumentsByCriteria(String esIndexName, SearchSourceBuilder sourceBuilder) {
        try {
            SearchHits searchHits = executeSearch(esIndexName, sourceBuilder);
            if (searchHits.getTotalHits().value > 0) {
                BulkResponse bulkResponse = deleteMatchingDocuments(esIndexName, searchHits);
                if (!bulkResponse.hasFailures()) {
                    logger.info("Documents matching the criteria deleted successfully from Elasticsearch.");
                } else {
                    logger.error("Some documents failed to delete from Elasticsearch.");
                }
            } else {
                logger.info("No documents match the criteria.");
            }
        } catch (Exception e) {
            logger.error("Error occurred during deleting documents by criteria from Elasticsearch.", e);
        }
    }

    private SearchHits executeSearch(String esIndexName, SearchSourceBuilder sourceBuilder)
            throws IOException {
        SearchRequest searchRequest = new SearchRequest(esIndexName);
        searchRequest.source(sourceBuilder);
        SearchResponse searchResponse =
                elasticsearchClient.search(searchRequest, RequestOptions.DEFAULT);
        return searchResponse.getHits();
    }

    private BulkResponse deleteMatchingDocuments(String esIndexName, SearchHits searchHits)
            throws IOException {
        BulkRequest bulkRequest = new BulkRequest();
        searchHits.forEach(
                hit -> bulkRequest.add(new DeleteRequest(esIndexName, Constants.INDEX_TYPE, hit.getId())));
        return elasticsearchClient.bulk(bulkRequest, RequestOptions.DEFAULT);
    }

    private boolean isRangeQuery(Map<String, Object> nestedMap) {
        return nestedMap.keySet().stream().anyMatch(key -> key.equals(Constants.SEARCH_OPERATION_GREATER_THAN_EQUALS) ||
                key.equals(Constants.SEARCH_OPERATION_LESS_THAN_EQUALS) || key.equals(Constants.SEARCH_OPERATION_GREATER_THAN) ||
                key.equals(Constants.SEARCH_OPERATION_LESS_THAN));
    }

    private QueryBuilder buildQueryPart(Map<String, Object> queryMap) {
        logger.info("Search:: buildQueryPart");
        if (queryMap == null || queryMap.isEmpty()) {
            return QueryBuilders.matchAllQuery();
        }
        for (Entry<String, Object> entry : queryMap.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            switch (key) {
                case Constants.BOOL:
                    return buildBoolQuery((Map<String, Object>) value);
                case Constants.TERM:
                    return buildTermQuery((Map<String, Object>) value);
                case Constants.TERMS:
                    return buildTermsQuery((Map<String, Object>) value);
                case Constants.MATCH:
                    return buildMatchQuery((Map<String, Object>) value);
                case Constants.RANGE:
                    return buildRangeQuery((Map<String, Object>) value);
                default:
                    throw new IllegalArgumentException(Constants.UNSUPPORTED_QUERY + key);
            }
        }

        return null;
    }

    private BoolQueryBuilder buildBoolQuery(Map<String, Object> boolMap) {
        logger.info("Search:: builderBoolQuery");
        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
        if (boolMap.containsKey(Constants.MUST)) {
            List<Map<String, Object>> mustList = (List<Map<String, Object>>) boolMap.get("must");
            mustList.forEach(must -> boolQueryBuilder.must(buildQueryPart(must)));
        }
        if (boolMap.containsKey(Constants.FILTER)) {
            List<Map<String, Object>> filterList = (List<Map<String, Object>>) boolMap.get("filter");
            filterList.forEach(filter -> boolQueryBuilder.filter(buildQueryPart(filter)));
        }
        if (boolMap.containsKey(Constants.MUST_NOT)) {
            List<Map<String, Object>> mustNotList = (List<Map<String, Object>>) boolMap.get("must_not");
            mustNotList.forEach(mustNot -> boolQueryBuilder.mustNot(buildQueryPart(mustNot)));
        }
        if (boolMap.containsKey(Constants.SHOULD)) {
            List<Map<String, Object>> shouldList = (List<Map<String, Object>>) boolMap.get("should");
            shouldList.forEach(should -> boolQueryBuilder.should(buildQueryPart(should)));
        }

        return boolQueryBuilder;
    }

    private QueryBuilder buildTermQuery(Map<String, Object> termMap) {
        logger.info("search::buildTermQuery");
        for (Entry<String, Object> entry : termMap.entrySet()) {
            return QueryBuilders.termQuery(entry.getKey(), entry.getValue());
        }
        return null;
    }

    private QueryBuilder buildTermsQuery(Map<String, Object> termsMap) {
        logger.info("search::buildTermsQuery");
        for (Entry<String, Object> entry : termsMap.entrySet()) {
            return QueryBuilders.termsQuery(entry.getKey(), (List<?>) entry.getValue());
        }
        return null;
    }

    private QueryBuilder buildMatchQuery(Map<String, Object> matchMap) {
        logger.info("search:: buildMatchQuery");
        for (Entry<String, Object> entry : matchMap.entrySet()) {
            return QueryBuilders.matchQuery(entry.getKey(), entry.getValue());
        }
        return null;
    }

    private QueryBuilder buildRangeQuery(Map<String, Object> rangeMap) {
        logger.info("search:: buildRangeQuery");
        for (Entry<String, Object> entry : rangeMap.entrySet()) {
            Map<String, Object> rangeConditions = (Map<String, Object>) entry.getValue();
            RangeQueryBuilder rangeQuery = QueryBuilders.rangeQuery(entry.getKey());
            rangeConditions.forEach((condition, value) -> {
                switch (condition) {
                    case "gt":
                        rangeQuery.gt(value);
                        break;
                    case "gte":
                        rangeQuery.gte(value);
                        break;
                    case "lt":
                        rangeQuery.lt(value);
                        break;
                    case "lte":
                        rangeQuery.lte(value);
                        break;
                    default:
                        throw new IllegalArgumentException(Constants.UNSUPPORTED_RANGE + condition);
                }
            });
            return rangeQuery;
        }
        return null;
    }

    @Override
    public boolean isIndexPresent(String indexName) {
        try {
            GetIndexRequest request = new GetIndexRequest(indexName);
            return elasticsearchClient.indices().exists(request, RequestOptions.DEFAULT);
        } catch (IOException e) {
            logger.error("Error checking if index exists", e);
            return false;
        }
    }

    @Override
    public BulkResponse saveAll(String esIndexName,
        String type,
        List<JsonNode> entities) throws IOException {
        try {
            logger.info("EsUtilServiceImpl :: saveAll");
            BulkRequest bulkRequest = new BulkRequest();
            entities.forEach(entity -> {
                String formattedId = entity.get(Constants.ID).asText();
                Map<String, Object> entityMap = objectMapper.convertValue(entity, Map.class);
                IndexRequest indexRequest = new IndexRequest(esIndexName, type, formattedId)
                    .source(entityMap, XContentType.JSON);
                bulkRequest.add(indexRequest);
            });

            RequestOptions options = RequestOptions.DEFAULT;
            return elasticsearchClient.bulk(bulkRequest, options);
        } catch (Exception e) {
            logger.error(e.getMessage());
            throw new CustomException("error bulk uploading", e.getMessage(),
                HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }


}

