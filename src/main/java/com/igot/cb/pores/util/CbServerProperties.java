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

    @Value("${search.string.max.regex.length}")
    private int searchStringMaxRegexLength;

    @Value("${elastic.required.field.community.json.path}")
    private String elasticCommunityJsonPath;

    @Value("${elastic.required.field.community.category.json.path}")
    private String elasticCommunityCategoryJsonPath;

    @Value("${report.community.user.limit}")
    private int reporCommunityUserLimit;
}
