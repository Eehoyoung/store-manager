package com.storemanager.api.user;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BusinessRegistryServiceTest {
    @Test
    void 국세청_키가_없으면_가입을_차단한다() {
        var service = new BusinessRegistryService(true, "", java.net.URI.create("http://127.0.0.1"),
                HttpClient.newHttpClient());
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE,
                assertThrows(ApiException.class, () -> service.verify("1234567890", "20200101", "김대표"))
                        .getErrorCode());
    }

    @Test
    void 국세청_진위확인과_휴폐업_실패닫힘() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/validate", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response = request.contains("20200101")
                    ? "{\"data\":[{\"valid\":\"01\",\"status\":{\"b_stt_cd\":\"01\"}}]}"
                    : "{\"data\":[{\"valid\":\"01\",\"status\":{\"b_stt_cd\":\"03\"}}]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            var service = new BusinessRegistryService(true, "test-key",
                    java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/validate"),
                    HttpClient.newHttpClient());
            assertDoesNotThrow(() -> service.verify("123-45-67890", "2020-01-01", "김대표"));
            ApiException ex = assertThrows(ApiException.class,
                    () -> service.verify("1234567890", "2021-01-01", "김대표"));
            assertEquals(ErrorCode.VALIDATION_FAILED, ex.getErrorCode());
            assertEquals(ErrorCode.VALIDATION_FAILED,
                    assertThrows(ApiException.class, () -> service.verify("123", "20200101", "김대표"))
                            .getErrorCode());
        } finally {
            server.stop(0);
        }
    }
}
