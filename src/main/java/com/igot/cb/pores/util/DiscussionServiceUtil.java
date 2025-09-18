package com.igot.cb.pores.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Component
public class DiscussionServiceUtil {
    private static final ObjectMapper mapper = new ObjectMapper();

    private final CbServerProperties cbServerProperties;

    @Autowired
    public DiscussionServiceUtil(CbServerProperties cbServerProperties) {
        this.cbServerProperties = cbServerProperties;
    }

    public static void createSuccessResponse(ApiResponse response) {
        response.getParams().setStatus(Constants.SUCCESS);
        response.setResponseCode(HttpStatus.OK);
    }

    public static void createErrorResponse(ApiResponse response, String errorMessage, HttpStatus httpStatus, String status) {
        response.getParams().setErrMsg(errorMessage);
        response.getParams().setStatus(status);
        response.setResponseCode(httpStatus);
    }

    public static String getFormattedCurrentTime(Timestamp currentTime) {
        ZonedDateTime zonedDateTime = currentTime.toInstant().atZone(ZoneId.systemDefault());
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(Constants.TIME_FORMAT);
        return zonedDateTime.format(formatter);
    }

    public String generateRedisJwtTokenKey(Object requestPayload) {
        if (requestPayload != null) {
            try {
                String reqJsonString = mapper.writeValueAsString(requestPayload);
                return JWT.create().withClaim(Constants.REQUEST_PAYLOAD, reqJsonString).sign(Algorithm.HMAC256(cbServerProperties.getJwtDemandSearchKeyName()));
            } catch (JsonProcessingException e) {
                log.error("Error occurred while converting json object to json string", e);
            }
        }
        return "";
    }
}