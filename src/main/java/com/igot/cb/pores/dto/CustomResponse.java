package com.igot.cb.pores.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CustomResponse {
  private String message;
  private RespParam params;
  private HttpStatus responseCode;
  private Map<String, Object> result = new HashMap<>();
  public RespParam getParams() {
    if (params == null) {
      params = new RespParam();
    }
    return params;
  }
}
