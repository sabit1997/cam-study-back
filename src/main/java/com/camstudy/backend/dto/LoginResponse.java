package com.camstudy.backend.dto;

public class LoginResponse {
  
  private Long userId;
  private String username;

  public LoginResponse(Long userId, String username) {
    this.userId = userId;
    this.username = username;
  }

  public Long getUserId() {
    return userId;
  }

  public String getUsername() {
    return username;
  }
}
