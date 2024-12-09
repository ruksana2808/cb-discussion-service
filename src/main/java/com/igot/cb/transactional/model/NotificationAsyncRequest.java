package com.igot.cb.transactional.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;
@Getter
@Setter
public class NotificationAsyncRequest {
    private String type;
    private int priority;
    private Map<String, Object> action;
    private List<String> ids;
    private List<String> copyEmail;

}
