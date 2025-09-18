package com.igot.cb.authentication.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.igot.cb.authentication.model.KeyData;
import com.igot.cb.pores.util.Constants;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.common.util.Time;
import org.keycloak.crypto.KeyWrapper;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccessTokenValidatorTest {

    @Mock
    private KeyManager keyManager;

    @Mock
    private KeyData mockKeyData;

    @Mock
    private KeyWrapper mockKeyWrapper;

    @Mock
    private PublicKey mockPublicKey;

    @InjectMocks
    private AccessTokenValidator accessTokenValidator;

    @Spy
    private AccessTokenValidator spyAccessTokenValidator;

    private static final ObjectMapper mapper = new ObjectMapper();

    private String expiredToken;
    private String invalidSignatureToken;
    private String invalidIssuerToken;

    @BeforeEach
    void setUp() throws Exception {
        // Mock PropertiesCache.getInstance().getProperty(...) if needed
        // Generate tokens for different scenarios
        expiredToken = generateToken("expiredUserId", Time.currentTime() - 1000, "expectedIssuer");
        invalidSignatureToken = generateToken("invalidSignatureUserId", Time.currentTime() + 1000, "expectedIssuer");
        invalidIssuerToken = generateToken("invalidIssuerUserId", Time.currentTime() + 1000, "invalidIssuer");

    }

    @Test
    void testVerifyUserToken_ExpiredToken() {
        String userId = accessTokenValidator.verifyUserToken(expiredToken);
        assertEquals(Constants.UNAUTHORIZED, userId);
    }

    @Test
    void testVerifyUserToken_InvalidSignature() {
        String userId = accessTokenValidator.verifyUserToken(invalidSignatureToken);
        assertEquals(Constants.UNAUTHORIZED, userId);
    }

    @Test
    void testVerifyUserToken_InvalidIssuer() {
        String userId = accessTokenValidator.verifyUserToken(invalidIssuerToken);
        assertEquals(Constants.UNAUTHORIZED, userId);
    }

    @Test
    void testFetchUserIdFromAccessToken_NullToken() {
        String userId = accessTokenValidator.fetchUserIdFromAccessToken(null);
        assertNull(userId);
    }

    private String generateToken(String userId, int exp, String issuer) throws Exception {
        Map<String, Object> header = new HashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", "testKeyId");
        Map<String, Object> body = new HashMap<>();
        body.put("sub", "user:" + userId);
        body.put("exp", exp);
        body.put("iss", issuer);
        String headerJson = mapper.writeValueAsString(header);
        String bodyJson = mapper.writeValueAsString(body);
        String encodedHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String encodedBody   = Base64.getUrlEncoder().withoutPadding().encodeToString(bodyJson.getBytes(StandardCharsets.UTF_8));
        String unsignedToken = encodedHeader + "." + encodedBody;
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("dummy".getBytes(StandardCharsets.UTF_8));
        return unsignedToken + "." + signature;
    }

    @Test
    void fetchUserIdFromAccessToken_validToken_returnsUserId() {
        String accessToken = "validToken";
        String expectedUserId = "user123";


        doReturn(expectedUserId).when(spyAccessTokenValidator).verifyUserToken(accessToken);

        String actualUserId = spyAccessTokenValidator.fetchUserIdFromAccessToken(accessToken);

        assertEquals(expectedUserId, actualUserId);
    }

    @Test
    void fetchUserIdFromAccessToken_unauthorizedToken_returnsNull() {
        String accessToken = "unauthorizedToken";

        doReturn("UNAUTHORIZED").when(spyAccessTokenValidator).verifyUserToken(accessToken);

        String actualUserId = spyAccessTokenValidator.fetchUserIdFromAccessToken(accessToken);

        assertNull(actualUserId);
    }

    @Test
    void fetchUserIdFromAccessToken_nullToken_returnsNull() {
        String actualUserId = spyAccessTokenValidator.fetchUserIdFromAccessToken(null);
        assertNull(actualUserId);
    }

    @Test
    void fetchUserIdFromAccessToken_exceptionThrown_returnsNull() {
        String accessToken = "token";

        doThrow(new RuntimeException("some error")).when(spyAccessTokenValidator).verifyUserToken(accessToken);

        String actualUserId = spyAccessTokenValidator.fetchUserIdFromAccessToken(accessToken);

        assertNull(actualUserId);
    }

    @Test
    void testVerifyUserToken_InvalidFormatToken() {
        String badToken = "only.two.parts";
        String userId = accessTokenValidator.verifyUserToken(badToken);
        assertEquals(Constants.UNAUTHORIZED, userId);
    }

    @Test
    void testVerifyUserToken_ValidToken() throws Exception {
        Field field = AccessTokenValidator.class.getDeclaredField("REALM_URL");
        field.setAccessible(true);
        String realmUrl = (String) field.get(null);
        String token = generateToken("validUser", Time.currentTime() + 1000, realmUrl);
        when(keyManager.getPublicKey(anyString())).thenReturn(mockKeyData);
        when(mockKeyData.getPublicKey()).thenReturn(mockPublicKey);
        try (MockedStatic<CryptoUtil> cryptoMock = mockStatic(CryptoUtil.class)) {
            cryptoMock.when(() -> CryptoUtil.verifyRSASign(any(), any(), any(), any()))
                    .thenReturn(true);
            String userId = accessTokenValidator.verifyUserToken(token);
            assertEquals("validUser", userId);
        }
    }

    @Test
    void testIsExpired_FutureExpiration() throws Exception {
        var method = AccessTokenValidator.class.getDeclaredMethod("isExpired", Integer.class);
        method.setAccessible(true);
        boolean result = (boolean) method.invoke(accessTokenValidator, Time.currentTime() + 1000);
        assertFalse(result);
    }

    @Test
    void testVerifyUserToken_InvalidJsonInHeader() {
        String badHeader = Base64.getUrlEncoder().withoutPadding().encodeToString("not-json".getBytes());
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"exp\":999999999}".getBytes());
        String token = badHeader + "." + body + ".sig";
        String userId = accessTokenValidator.verifyUserToken(token);
        assertEquals(Constants.UNAUTHORIZED, userId);
    }

    @Test
    void testCheckIss_NullIssuer() {
        boolean result = invokeCheckIss(null);
        assertFalse(result);
    }

    private boolean invokeCheckIss(String iss) {
        try {
            var method = AccessTokenValidator.class.getDeclaredMethod("checkIss", String.class);
            method.setAccessible(true);
            return (boolean) method.invoke(accessTokenValidator, iss);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testCheckIss_ValidIssuer() throws Exception {
        Field field = AccessTokenValidator.class.getDeclaredField("REALM_URL");
        field.setAccessible(true); // allow access to private static final
        String realmUrl = (String) field.get(null);
        String token = generateToken("userY", Time.currentTime() + 1000, realmUrl);
        when(keyManager.getPublicKey(anyString())).thenReturn(mockKeyData);
        when(mockKeyData.getPublicKey()).thenReturn(mockPublicKey);
        try (MockedStatic<CryptoUtil> cryptoMock = mockStatic(CryptoUtil.class)) {
            cryptoMock.when(() -> CryptoUtil.verifyRSASign(any(), any(), any(), any()))
                    .thenReturn(true);
            String userId = accessTokenValidator.verifyUserToken(token);
            assertEquals("userY", userId);
        }
    }

    @Test
    void testVerifyUserToken_CryptoUtilThrowsException() throws Exception {
        String token = generateToken("userErr", Time.currentTime() + 1000, "expectedIssuer");
        when(keyManager.getPublicKey(anyString())).thenReturn(mockKeyData);
        when(mockKeyData.getPublicKey()).thenReturn(mockPublicKey);
        try (MockedStatic<CryptoUtil> cryptoMock = mockStatic(CryptoUtil.class)) {
            cryptoMock.when(() -> CryptoUtil.verifyRSASign(any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("crypto failure"));
            String userId = accessTokenValidator.verifyUserToken(token);
            assertEquals(Constants.UNAUTHORIZED, userId);
        }
    }

    @Test
    void testCheckIss_IssuerMismatch() {
        boolean result = invokeCheckIss("someOtherIssuer");
        assertFalse(result);
    }

    @Test
    void testIsExpired_PastExpiration() throws Exception {
        var method = AccessTokenValidator.class.getDeclaredMethod("isExpired", Integer.class);
        method.setAccessible(true);
        boolean result = (boolean) method.invoke(accessTokenValidator, Time.currentTime() - 10);
        assertTrue(result);
    }

    @Test
    void testVerifyUserToken_InvalidJsonInBody() {
        String goodHeader = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"kid\":\"kid1\"}".getBytes());
        String badBody = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("not-json".getBytes());
        String token = goodHeader + "." + badBody + ".sig";
        String userId = accessTokenValidator.verifyUserToken(token);
        assertEquals(Constants.UNAUTHORIZED, userId);
    }
}
