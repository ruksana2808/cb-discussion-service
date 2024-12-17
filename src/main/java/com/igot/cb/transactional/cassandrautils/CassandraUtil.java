package com.igot.cb.transactional.cassandrautils;

import com.datastax.driver.core.ColumnDefinitions;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.igot.cb.pores.util.Constants;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Mahesh RV
 * @author Ruksana
 */
public final class CassandraUtil {

    private CassandraUtil() {
    }

    private static final CassandraPropertyReader propertiesCache = CassandraPropertyReader.getInstance();


    public static String getPreparedStatement(
            String keyspaceName, String tableName, Map<String, Object> map) {
        StringBuilder query = new StringBuilder();
        query.append(Constants.INSERT_INTO).append(keyspaceName).append(Constants.DOT).append(tableName).append(Constants.OPEN_BRACE);
        Set<String> keySet = map.keySet();
        query.append(String.join(",", keySet)).append(Constants.VALUES_WITH_BRACE);
        StringBuilder commaSepValueBuilder = new StringBuilder();
        for (int i = 0; i < keySet.size(); i++) {
            commaSepValueBuilder.append(Constants.QUE_MARK);
            if (i != keySet.size() - 1) {
                commaSepValueBuilder.append(Constants.COMMA);
            }
        }
        query.append(commaSepValueBuilder).append(Constants.CLOSING_BRACE);
        return query.toString();
    }


    public static List<Map<String, Object>> createResponse(ResultSet results) {
        List<Map<String, Object>> responseList = new ArrayList<>();
        Map<String, String> columnsMapping = fetchColumnsMapping(results);
        Iterator<Row> rowIterator = results.iterator();
        rowIterator.forEachRemaining(
                row -> {
                    Map<String, Object> rowMap = new HashMap<>();
                    columnsMapping
                            .forEach((key, value) -> rowMap.put(key, row.getObject(value)));
                    responseList.add(rowMap);
                });
        return responseList;
    }

    public static Map<String, Object> createResponse(ResultSet results, String key) {
        Map<String, Object> responseList = new HashMap<>();
        Map<String, String> columnsMapping = fetchColumnsMapping(results);
        Iterator<Row> rowIterator = results.iterator();
        rowIterator.forEachRemaining(
                row -> {
                    Map<String, Object> rowMap = new HashMap<>();
                    columnsMapping
                            .forEach((key1, value) -> rowMap.put(key1, row.getObject(value)));

                    responseList.put((String) rowMap.get(key), rowMap);
                });
        return responseList;
    }

    public static Map<String, String> fetchColumnsMapping(ResultSet results) {
        return results
                .getColumnDefinitions()
                .asList()
                .stream()
                .collect(
                        Collectors.toMap(
                                d -> propertiesCache.readProperty(d.getName()).trim(),
                                ColumnDefinitions.Definition::getName));
    }
}
