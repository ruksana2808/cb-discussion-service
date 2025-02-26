package com.igot.cb.pores.util;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
@Getter
@Setter
public class CbServerProperties {

  @Value("${search.result.redis.ttl}")
  private long searchResultRedisTtl;

  @Value("${elastic.required.field.discussion.json.path}")
  private String elasticDiscussionJsonPath;

  @Value("${discussion.entity}")
  private String discussionEntity;

  @Value("${discussion.cloud.folder.name}")
  private String discussionCloudFolderName;

  @Value("${discussion.container.name}")
  private String discussionContainerName;

  @Value("${cloud.storage.type.name}")
  private String cloudStorageTypeName;

  @Value("${cloud.storage.key}")
  private String cloudStorageKey;

  @Value("${cloud.storage.secret}")
  private String cloudStorageSecret;

  @Value("${cloud.storage.endpoint}")
  private String cloudStorageEndpoint;

}
