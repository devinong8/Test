package com.hackathon.inventory.web;

import com.hackathon.inventory.TestSupport;
import com.hackathon.inventory.domain.AppUser;
import com.hackathon.inventory.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** The browser's real flow over HTTP: XSRF cookie -> login -> session cookie -> API -> logout. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginFlowHttpTest extends TestSupport {
    @LocalServerPort int port;
    @Autowired PasswordEncoder encoder;

    final CookieManager cookies = new CookieManager();
    final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

    HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json");
        cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("XSRF-TOKEN")).findFirst()
                .map(HttpCookie::getValue).ifPresent(v -> b.header("X-XSRF-TOKEN", v));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void fullBrowserLoginFlow() throws Exception {
        userRepo.save(new AppUser("hq.admin", encoder.encode("s3cret!"), "HQ", Role.HQ_ADMIN, null, null));

        assertThat(send("GET", "/api/shops", null).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/api/auth/me", null).statusCode()).isEqualTo(401);
        assertThat(cookies.getCookieStore().getCookies()).anyMatch(c -> c.getName().equals("XSRF-TOKEN"));

        var bad = send("POST", "/api/auth/login", "{\"username\":\"hq.admin\",\"password\":\"nope\"}");
        assertThat(bad.statusCode()).isEqualTo(401);
        assertThat(bad.body()).contains("BAD_CREDENTIALS");

        var ok = send("POST", "/api/auth/login", "{\"username\":\"hq.admin\",\"password\":\"s3cret!\"}");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("\"role\":\"HQ_ADMIN\"");
        assertThat(ok.headers().allValues("Set-Cookie")).anyMatch(c -> c.startsWith("JSESSIONID") && c.contains("HttpOnly"));

        send("GET", "/api/auth/me", null);   // picks up the rotated CSRF token
        assertThat(send("GET", "/api/auth/me", null).body()).contains("hq.admin");
        assertThat(send("GET", "/api/shops", null).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/api/regions", "{\"code\":\"X\",\"name\":\"X\",\"hqCity\":\"Y\"}").statusCode()).isEqualTo(201);

        assertThat(send("POST", "/api/auth/logout", null).statusCode()).isEqualTo(204);
        assertThat(send("GET", "/api/shops", null).statusCode()).isEqualTo(401);
    }
}
