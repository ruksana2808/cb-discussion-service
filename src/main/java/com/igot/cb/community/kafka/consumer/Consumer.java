package com.igot.cb.community.kafka.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.community.entity.CommunityEntity;
import com.igot.cb.community.repository.CommunityEngagementRepository;
import com.igot.cb.pores.cache.CacheService;
import com.igot.cb.pores.elasticsearch.service.EsUtilService;
import com.igot.cb.pores.util.CbServerProperties;
import com.igot.cb.pores.util.Constants;
import com.igot.cb.transactional.cassandrautils.CassandraOperation;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class Consumer {

  private ObjectMapper mapper = new ObjectMapper();

  @Autowired
  CassandraOperation cassandraOperation;

  @Autowired
  private CommunityEngagementRepository communityEngagementRepository;

  @Autowired
  private EsUtilService esUtilService;

  @Autowired
  private CbServerProperties cbServerProperties;

  @Autowired
  private CacheService cacheService;

  @Autowired
  private ObjectMapper objectMapper;



  @KafkaListener(groupId = "${kafka.topic.community.group}", topics = "${kafka.topic.community.user.count}")
  public void upateUserCount(ConsumerRecord<String, String> data) {
    try {
      Map<String, Object> updateUserCount = mapper.readValue(data.value(), Map.class);
      updateJoinedUserCount(updateUserCount);
    } catch (Exception e) {
      log.error("Failed to update the userCount" + data.value(), e);
    }
  }

  public void updateJoinedUserCount(Map<String, Object> updateUserCount) {
    Map<String, Object> propertyMap = new HashMap<>();
    CommunityEntity communityEntity = objectMapper.convertValue(updateUserCount.get(Constants.COMMUNITY), CommunityEntity.class);
    String userId = (String) updateUserCount.get(Constants.USER_ID);
    propertyMap.put(Constants.USER_ID, userId);
    propertyMap.put(Constants.CommunityId, communityEntity.getCommunityId());
    propertyMap.put(Constants.STATUS, true);
    cassandraOperation.insertRecord(Constants.KEYSPACE_SUNBIRD, Constants.USER_COMMUNITY_LOOK_UP_TABLE, propertyMap);
    ObjectNode dataNode = (ObjectNode) communityEntity.getData();

// Perform the update
    long currentCount = dataNode.get(Constants.COUNT_OF_PEOPLE_JOINED).asLong();
    dataNode.put(Constants.COUNT_OF_PEOPLE_JOINED, currentCount + 1);Timestamp currentTime = new Timestamp(System.currentTimeMillis());
    communityEntity.setUpdatedOn(currentTime);
    dataNode.put(Constants.UPDATED_ON, String.valueOf(currentTime));
    dataNode.put(Constants.UPDATED_BY, userId);
    dataNode.put(Constants.STATUS, Constants.ACTIVE);
    dataNode.put(Constants.COMMUNITY_ID, communityEntity.getCommunityId());
    communityEngagementRepository.save(communityEntity);
    Map<String, Object> map = objectMapper.convertValue(dataNode, Map.class);
    esUtilService.updateDocument(Constants.INDEX_NAME, Constants.INDEX_TYPE,
        communityEntity.getCommunityId(), map,
        cbServerProperties.getElasticCommunityJsonPath());
    cacheService.putCache(Constants.REDIS_KEY_PREFIX, communityEntity.getData());

  }

}
