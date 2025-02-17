package com.igot.cb.pores.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.igot.cb.pores.util.Constants;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

@Service
@Slf4j
public class CacheService {

  @Autowired
  private JedisPool jedisPool;
  @Autowired
  private ObjectMapper objectMapper;

  @Value("${spring.redis.cacheTtl}")
  private long cacheTtl;

  public Jedis getJedis() {
    try (Jedis jedis = jedisPool.getResource()) {
      return jedis;
    }
  }

  public void putCache(String key, Object object) {
    try {
      String data = objectMapper.writeValueAsString(object);
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.set(Constants.REDIS_KEY_PREFIX + key, data);
        jedis.expire(Constants.REDIS_KEY_PREFIX + key, cacheTtl);
      }
    } catch (Exception e) {
      log.error("Error while putting data in Redis cache: {} ", e.getMessage());
    }
  }

  public String getCache(String key) {
    try {
      return getJedis().get(Constants.REDIS_KEY_PREFIX + key);
    } catch (Exception e) {
      return null;
    }
  }

  public Long deleteCache(String key) {
    try (Jedis jedis = jedisPool.getResource()) {
      Long result = jedis.del(Constants.REDIS_KEY_PREFIX + key);
      if (result == 1) {
        log.info("Field {} deleted successfully from key {}.", key);
      } else {
        log.warn("Field {} not found in key {}.", key);
      }
      return result;
    } catch (Exception e) {
      log.error("Error while deleting data from Redis cache: {} ", e.getMessage());
      return null;
    }
  }

  public void addUsersToHash(String key, Set<String> userIds) {
    try (Jedis jedis = jedisPool.getResource()) {
      // Prepare the user map
      Map<String, String> userMap = new HashMap<>();
      for (String userId : userIds) {
        userMap.put(userId, userId); // Using userId as both field and value
      }
      // Add fields to the hash only if they do not exist
      long fieldsAdded = 0;
      for (Map.Entry<String, String> entry : userMap.entrySet()) {
        long result = jedis.hsetnx(key, entry.getKey(), entry.getValue());
        fieldsAdded += result;
      }
    } catch (Exception e) {
      log.error("Error while adding users to Redis Hash: {}", e.getMessage());
    }
  }


  public List<String> getPaginatedUsersFromHash(String key, int offset, int limit) {
    try (Jedis jedis = jedisPool.getResource()) {
      // Retrieve all users from the Redis hash
      Map<String, String> allUsers = jedis.hgetAll(key);
      List<String> userIdList = new ArrayList<>(allUsers.keySet());

      // Sort users if needed
      Collections.sort(userIdList);

      // Calculate the starting index
      int startIndex = offset * limit;

      // Calculate the ending index
      int endIndex = Math.min(startIndex + limit, userIdList.size());

      // Apply pagination logic
      if (startIndex < userIdList.size()) {
        return userIdList.subList(startIndex, endIndex);
      } else {
        return Collections.emptyList();
      }
    } catch (Exception e) {
      log.error("Error while fetching paginated users from Redis Hash: {}", e.getMessage());
      return Collections.emptyList();
    }
  }


  public Long getListSize(String key) {
    try (Jedis jedis = jedisPool.getResource()) {
      return jedis.hlen(key);
    } catch (Exception e) {
      log.error("Error while fetching list size from Redis: {}", e.getMessage());
      return null;
    }
  }

  public void upsertUserToHash(String key, String field, String value) {
    try (Jedis jedis = jedisPool.getResource()) {
      long result = jedis.hset(key, field, value);;

      if (result == 1) {
        log.info("Field '{}' added to hash '{}'", field, key);
      } else {
        log.warn("Field '{}' already exists in hash '{}'", field, key);
      }
    } catch (Exception e) {
      log.error("Error while upserting user to Redis Hash: {}", e.getMessage());
    }
  }

  public void deleteUserFromHash(String key, String field) {
    try (Jedis jedis = jedisPool.getResource()) {
      // Remove the specified field from the hash
      long fieldsRemoved = jedis.hdel(key, field);

      if (fieldsRemoved > 0) {
        log.info("Field '{}' removed from hash '{}'", field, key);
      } else {
        log.warn("Field '{}' does not exist in hash '{}'", field, key);
      }
    } catch (Exception e) {
      log.error("Error while deleting field from Redis Hash: {}", e.getMessage());
    }
  }


}
