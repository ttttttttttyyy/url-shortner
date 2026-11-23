package com.url.shortly.service;

import com.url.shortly.DTO.LoginRequest;
import com.url.shortly.DTO.RegisterRequest;
import com.url.shortly.DTO.UserProfile;
import com.url.shortly.security.ClientMetadata;

public interface AuthService {

    AuthResult register(RegisterRequest request, ClientMetadata metadata);

    AuthResult login(LoginRequest request, ClientMetadata metadata);

    AuthResult refresh(String rawRefreshToken, ClientMetadata metadata);

    void logout(String rawRefreshToken);

    UserProfile profile(Long userId);
}
