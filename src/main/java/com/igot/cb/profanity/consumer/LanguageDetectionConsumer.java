package com.igot.cb.profanity.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.discussion.repository.DiscussionAnswerPostReplyRepository;
import com.igot.cb.discussion.repository.DiscussionRepository;
import com.igot.cb.pores.util.CbServerProperties;
import com.igot.cb.pores.util.Constants;
import com.igot.cb.profanity.IProfanityCheckService;
import com.igot.cb.transactional.service.RequestHandlerServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class LanguageDetectionConsumer {

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private CbServerProperties cbServerProperties;

    @Autowired
    private RequestHandlerServiceImpl requestHandlerService;

    @Autowired
    private IProfanityCheckService profanityCheckService;

    @Autowired
    private DiscussionRepository discussionRepository;

    @Autowired
    private DiscussionAnswerPostReplyRepository discussionAnswerPostReplyRepository;

    @KafkaListener(topics = "${kafka.topic.process.detect.language}", groupId = "${kafka.group.process.detect.language}")
    public void checkTextLanguage(ConsumerRecord<String, String> textData) {
        if (StringUtils.hasText(textData.value())) {
            try {
                ObjectNode discussionDetailsNode = (ObjectNode) mapper.readTree(textData.value());
                String id = discussionDetailsNode.get(Constants.DISCUSSION_ID).asText();
                String text = discussionDetailsNode.get(Constants.DESCRIPTION).asText();
                String detectedLanguage = "";
                detectedLanguage = detectLanguage(detectedLanguage, discussionDetailsNode, id, text);
                if (detectedLanguage == null) return;
                discussionDetailsNode.put(Constants.LANGUAGE, detectedLanguage);
                profanityCheckService.processProfanityCheck(String.valueOf(id), discussionDetailsNode);
            } catch (JsonProcessingException e) {
                log.error("Failed to parse JSON from Kafka message: {}", textData.value(), e);
            }
        }
    }

    /**
     * Handles the case where language detection fails for a discussion.
     * Updates the discussion status to indicate that language detection was not successful.
     *
     * @param discussionDetailsNode the details of the discussion as an ObjectNode
     * @param id the ID of the discussion
     */
    private void handleLanguageDetectionFailure(ObjectNode discussionDetailsNode, String id, String updateStatus) {
        if (Constants.QUESTION.equalsIgnoreCase(discussionDetailsNode.get(Constants.TYPE).asText()) || Constants.ANSWER_POST.equalsIgnoreCase(discussionDetailsNode.get(Constants.TYPE).asText())) {
            discussionRepository.updateProfanityCheckStatusByDiscussionId(id, updateStatus, false);
        } else if (Constants.ANSWER_POST_REPLY.equalsIgnoreCase(discussionDetailsNode.get(Constants.TYPE).asText())) {
            discussionAnswerPostReplyRepository.updateProfanityCheckStatusByDiscussionId(id, updateStatus, false);
        }
    }

    /**
     * Calls the language detection service to detect the language of the given text.
     *
     * @param discussionDetailsNode the details of the discussion as an ObjectNode
     * @param id the ID of the discussion
     * @param text the text for which language detection is to be performed
     * @return a map containing the response from the language detection service
     */
    private Map<String, Object> callLanguageDetectionService(ObjectNode discussionDetailsNode, String id, String text) {
        Map<String, Object> langDetectBody = new HashMap<>();
        langDetectBody.put(Constants.TEXT, text);
        Map<String, String> langDetectHeaders = new HashMap<>();
        langDetectHeaders.put(Constants.CONTENT_TYPE, Constants.APPLICATION_JSON);
        langDetectHeaders.put(Constants.AUTHORIZATION, cbServerProperties.getCbDiscussionApiKey());
        Map<String, Object> langDetectResponse = new HashMap<>();
        try {
            langDetectResponse = requestHandlerService.fetchResultUsingPost(
                    cbServerProperties.getContentModerationServiceUrl() + "/" + cbServerProperties.getContentModerationLanguageDetectApiPath(), langDetectBody,
                    langDetectHeaders
            );
        } catch (Exception e) {
            log.error("Exception while calling language detection service for text: {}", text, e);
            handleLanguageDetectionFailure(discussionDetailsNode, id, Constants.LANGUAGE_DETECTION_CALL_FAILED);
            return new HashMap<>();
        }
        return langDetectResponse;
    }


    /**
     * Detects the language of the given text using a language detection service.
     * If the service call fails or returns an empty language, handles the failure accordingly.
     *
     * @param detectedLanguage the initially detected language (can be empty)
     * @param discussionDetailsNode the details of the discussion as an ObjectNode
     * @param id the ID of the discussion
     * @param text the text for which language detection is to be performed
     * @return the detected language, or null if detection failed
     */
    private String detectLanguage(String detectedLanguage, ObjectNode discussionDetailsNode, String id, String text) {
        if (cbServerProperties.isEnableEnglishLanguageByDefault()) {
            log.info("Setting default language as English for discussion ID: {}", id);
            detectedLanguage = Constants.ENGLISH_LANGUAGE_CODE;
        } else {
            Map<String, Object> langDetectResponse = callLanguageDetectionService(discussionDetailsNode, id, text);
            if (MapUtils.isNotEmpty(langDetectResponse)) {
                Object lang = langDetectResponse.get(Constants.DETECTED_LANGUAGE);
                if (lang != null) {
                    detectedLanguage = lang.toString();
                }
            }
            if (!StringUtils.hasText(detectedLanguage)) {
                log.warn("Detected language is empty for discussion ID: {}", id);
                handleLanguageDetectionFailure(discussionDetailsNode, id, Constants.LANGUAGE_NOT_DETECTED);
                return null;
            }
        }
        return detectedLanguage;
    }
}
