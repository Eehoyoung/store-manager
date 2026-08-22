package com.storemanager.api.internal;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InternalTokenVerifier {

    private final byte[] expectedToken;

    public InternalTokenVerifier(@Value("${app.internal.token}") String expectedToken) {
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    public void verify(String token) {
        if (token == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), expectedToken)) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
    }
}
