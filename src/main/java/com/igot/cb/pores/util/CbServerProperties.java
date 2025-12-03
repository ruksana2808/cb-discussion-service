package com.igot.cb.pores.util;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

  @Value("${report.post.user.limit}")
  private int reportPostUserLimit;

  @Value("${discussion.es.defaultPageSize}")
  private int discussionEsDefaultPageSize;

  @Value("${discussion.feed.redis.ttl}")
  private long discussionFeedRedisTtl;

  @Value("${discussion.report.hide.post}")
  private boolean discussionReportHidePost;

  @Value("${filter.criteria.trending.feed}")
  private String filterCriteriaTrendingFeed;

  @Value("${elastic.required.field.community.json.path}")
  private String elasticCommunityJsonPath;

  @Value("${community.entity}")
  private String communityEntity;

  @Value("${kafka.topic.community.discusion.post.count}")
  private String communityPostCount;

  @Value("${kafka.topic.community.discusion.like.count}")
  private String communityLikeCount;

  @Value("${filter.criteria.global.feed}")
  private String filterCriteriaForGlobalFeed;

  @Value("${filter.criteria.mdo.all.report.feed}")
  private String mdoAllReportFeed;

  @Value("${filter.criteria.mdo.report.question.feed}")
  private String mdoQuestionReportFeed;

  @Value("${filter.criteria.mdo.report.answerPost.feed}")
  private String mdoAnswerPostReportFeed;

  @Value("${filter.criteria.mdo.report.answerPostReply.feed}")
  private String mdoAnswerPostReplyReportFeed;

  @Value("${filter.criteria.mdo.all.suspended.feed}")
  private String mdoAllSuspendedFeed;

  @Value("${filter.criteria.question.document.feed}")
  private String filterCriteriaQuestionDocumentFeed;

  @Value("${filter.criteria.question.user.feed}")
  private String filterCriteriaQuestionUserFeed;

  @Value("${user.feed.filter.criteriaMapSize}")
  private int userFeedFilterCriteriaMapSize;

  @Value("${kafka.topic.user.post.count}")
  private String kafkaUserPostCount;

  @Value("${cb.service.registry.base.url}")
  private String cbServiceRegistryBaseUrl;

  @Value("${cb.registry.textmoderation.api.path}")
  private String cbRegistryTextModerationApiPath;

  @Value("${cb.discussion.api.key}")
  private String cbDiscussionApiKey;

  @Value("${content.moderation.language.detect.api.path}")
  private String contentModerationLanguageDetectApiPath;

  @Value("${kafka.topic.process.detect.language}")
  private String kafkaProcessDetectLanguageTopic;

  @Value("${kafka.group.process.detect.language}")
  private String kafkaGroupProcessDetectLanguageGroup;

  @Value("${content.moderation.service.url}")
  private String contentModerationServiceUrl;

  @Value("${enable.english.language.by.default}")
  private boolean enableEnglishLanguageByDefault;

  @Value("${jwt.demand.search.key.name}")
  private String jwtDemandSearchKeyName;

  @Value("${redis.scan.count.size}")
  private int redisScanCountSize;

}
