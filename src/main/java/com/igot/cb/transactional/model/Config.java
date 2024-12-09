package com.igot.cb.transactional.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Config {
    private String sender;
    private Object topic;
    private Object otp;
    private String subject;

}
