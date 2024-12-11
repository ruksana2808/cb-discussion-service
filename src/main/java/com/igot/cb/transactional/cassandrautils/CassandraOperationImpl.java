package com.igot.cb.transactional.cassandrautils;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.querybuilder.Clause;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.datastax.driver.core.querybuilder.Select;
import com.datastax.driver.core.querybuilder.Select.Builder;
import com.datastax.driver.core.querybuilder.Select.Where;
import com.igot.cb.pores.util.ApiResponse;
import com.igot.cb.pores.util.Constants;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.text.MessageFormat;
import java.util.*;
import java.util.Map.Entry;


/**
 * @author Mahesh RV
 * @author Ruksana
 */
@Component
public class CassandraOperationImpl implements CassandraOperation {

    private Logger logger = LoggerFactory.getLogger(getClass().getName());

    @Autowired
    CassandraConnectionManager connectionManager;

    private Select processQuery(String keyspaceName, String tableName, Map<String, Object> propertyMap,
                                List<String> fields) {
        Select selectQuery = null;

        Builder selectBuilder;
        if (CollectionUtils.isNotEmpty(fields)) {
            String[] dbFields = fields.toArray(new String[fields.size()]);
            selectBuilder = QueryBuilder.select(dbFields);
        } else {
            selectBuilder = QueryBuilder.select().all();
        }
        selectQuery = selectBuilder.from(keyspaceName, tableName);
        if (MapUtils.isNotEmpty(propertyMap)) {
            Where selectWhere = selectQuery.where();
            for (Entry<String, Object> entry : propertyMap.entrySet()) {
                if (entry.getValue() instanceof List) {
                    List<Object> list = (List) entry.getValue();
                    if (null != list) {
                        Object[] propertyValues = list.toArray(new Object[list.size()]);
                        Clause clause = QueryBuilder.in(entry.getKey(), propertyValues);
                        selectWhere.and(clause);

                    }
                } else {

                    Clause clause = QueryBuilder.eq(entry.getKey(), entry.getValue());
                    selectWhere.and(clause);

                }
                selectQuery.allowFiltering();
            }
        }
        return selectQuery;
    }

    private Select processQueryWithoutFiltering(String keyspaceName, String tableName, Map<String, Object> propertyMap,
                                                List<String> fields) {
        Select selectQuery = null;
        Builder selectBuilder;
        if (CollectionUtils.isNotEmpty(fields)) {
            String[] dbFields = fields.toArray(new String[fields.size()]);
            selectBuilder = QueryBuilder.select(dbFields);
        } else {
            selectBuilder = QueryBuilder.select().all();
        }
        selectQuery = selectBuilder.from(keyspaceName, tableName);
        if (MapUtils.isNotEmpty(propertyMap)) {
            Where selectWhere = selectQuery.where();
            for (Entry<String, Object> entry : propertyMap.entrySet()) {
                if (entry.getValue() instanceof List) {
                    List<Object> list = (List) entry.getValue();
                    if (null != list) {
                        Object[] propertyValues = list.toArray(new Object[list.size()]);
                        Clause clause = QueryBuilder.in(entry.getKey(), propertyValues);
                        selectWhere.and(clause);
                    }
                } else {
                    Clause clause = QueryBuilder.eq(entry.getKey(), entry.getValue());
                    selectWhere.and(clause);
                }
            }
        }
        return selectQuery;
    }

    @Override
    public List<Map<String, Object>> getRecordsByPropertiesByKey(String keyspaceName,
                                                                 String tableName, Map<String, Object> propertyMap, List<String> fields, String key) {
        Select selectQuery = null;
        List<Map<String, Object>> response = new ArrayList<>();
        try {
            selectQuery = processQuery(keyspaceName, tableName, propertyMap, fields);
            ResultSet results = connectionManager.getSession(keyspaceName).execute(selectQuery);
            response = CassandraUtil.createResponse(results);
            logger.info(response.toString());

        } catch (Exception e) {
            logger.error(Constants.EXCEPTION_MSG_FETCH + tableName + " : " + e.getMessage(), e);
        }
        return response;
    }

    @Override
    public Object insertRecord(String keyspaceName, String tableName, Map<String, Object> request) {
        ApiResponse response = new ApiResponse();
        String query = CassandraUtil.getPreparedStatement(keyspaceName, tableName, request);
        try {
            PreparedStatement statement = connectionManager.getSession(keyspaceName).prepare(query);
            BoundStatement boundStatement = new BoundStatement(statement);
            Iterator<Object> iterator = request.values().iterator();
            Object[] array = new Object[request.keySet().size()];
            int i = 0;
            while (iterator.hasNext()) {
                array[i++] = iterator.next();
            }
            connectionManager.getSession(keyspaceName).execute(boundStatement.bind(array));
            response.put(Constants.RESPONSE, Constants.SUCCESS);
        } catch (Exception e) {
            String errMsg = String.format("Exception occurred while inserting record to %s %s", tableName, e.getMessage());
            logger.error(errMsg);
            response.put(Constants.RESPONSE, Constants.FAILED);
            response.put(Constants.ERROR_MESSAGE, errMsg);
        }
        return response;
    }

    @Override
    public List<Map<String, Object>> getRecordsByPropertiesWithoutFiltering(String keyspaceName, String tableName, Map<String, Object> propertyMap, List<String> fields, Integer limit) {
        Select selectQuery = null;
        List<Map<String, Object>> response = new ArrayList<>();
        try {
            selectQuery = processQueryWithoutFiltering(keyspaceName, tableName, propertyMap, fields);
            if (limit != null) {
                selectQuery = selectQuery.limit(limit);
            }
            ResultSet results = connectionManager.getSession(keyspaceName).execute(selectQuery);
            response = CassandraUtil.createResponse(results);

        } catch (Exception e) {
            logger.error(Constants.EXCEPTION_MSG_FETCH + tableName + " : " + e.getMessage(), e);
        }
        return response;
    }

    @Override
    public Map<String,Object> updateRecord(
            String keyspaceName, String tableName, Map<String, Object> request) {
        long startTime = System.currentTimeMillis();
        logger.debug("Cassandra Service updateRecord method started at ==" + startTime);
        Map<String,Object> response = new HashMap<>();
        String query = getUpdateQueryStatement(keyspaceName, tableName, request);
        try {
            PreparedStatement statement = connectionManager.getSession(keyspaceName).prepare(query);
            Object[] array = new Object[request.size()];
            int i = 0;
            String str = "";
            int index = query.lastIndexOf(Constants.SET.trim());
            str = query.substring(index + 4);
            str = str.replace(Constants.EQUAL_WITH_QUE_MARK, "");
            str = str.replace(Constants.WHERE_ID, "");
            str = str.replace(Constants.SEMICOLON, "");
            String[] arr = str.split(",");
            for (String key : arr) {
                array[i++] = request.get(key.trim());
            }
            array[i] = request.get(Constants.ID);
            BoundStatement boundStatement = statement.bind(array);
            connectionManager.getSession(keyspaceName).execute(boundStatement);
            response.put(Constants.RESPONSE, Constants.SUCCESS);
            if (tableName.equalsIgnoreCase(Constants.USER)) {
                logger.info("Cassandra Service updateRecord in user table :" + request);
            }
        } catch (Exception e) {
            if (e.getMessage().contains(Constants.UNKNOWN_IDENTIFIER)) {
                logger.error(
                        Constants.EXCEPTION_MSG_UPDATE + tableName + " : " + e.getMessage(), e);
                String errMsg = String.format("Exception occurred while updating record to to %s %s", tableName, e.getMessage());
                response.put(Constants.RESPONSE, Constants.FAILED);
                response.put(Constants.ERROR_MESSAGE, errMsg);
            }
            logger.error(Constants.EXCEPTION_MSG_UPDATE + tableName + " : " + e.getMessage(), e);
        } finally {
            logQueryElapseTime("updateRecord", startTime, query);
        }
        return response;
    }

    public static String getUpdateQueryStatement(
            String keyspaceName, String tableName, Map<String, Object> map) {
        StringBuilder query =
                new StringBuilder(
                        Constants.UPDATE + keyspaceName + Constants.DOT + tableName + Constants.SET);
        Set<String> key = new HashSet<>(map.keySet());
        key.remove(Constants.ID);
        query.append(String.join(" = ? ,", key));
        query.append(
                Constants.EQUAL_WITH_QUE_MARK + Constants.WHERE_ID + Constants.EQUAL_WITH_QUE_MARK);
        return query.toString();
    }

    protected void logQueryElapseTime(
            String operation, long startTime, String query) {
        logger.info("Cassandra query : " + query);
        long stopTime = System.currentTimeMillis();
        long elapsedTime = stopTime - startTime;
        String message =
                "Cassandra operation {0} started at {1} and completed at {2}. Total time elapsed is {3}.";
        MessageFormat mf = new MessageFormat(message);
        logger.debug(mf.format(new Object[] {operation, startTime, stopTime, elapsedTime}));
    }
}
