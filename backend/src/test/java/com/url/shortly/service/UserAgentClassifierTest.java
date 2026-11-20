package com.url.shortly.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.url.shortly.service.UserAgentClassifier.ClientInfo;

/** FR-10: coarse, best-effort user-agent classification. */
class UserAgentClassifierTest {

    @Test
    @DisplayName("Common desktop and mobile agents are classified")
    void commonAgents() {
        assertClassify(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Desktop", "Windows", "Chrome");
        assertClassify(
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
                        + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
                "Mobile", "iOS", "Safari");
        assertClassify(
                "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
                        + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
                "Tablet", "iOS", "Safari");
        assertClassify(
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                "Mobile", "Android", "Chrome");
    }

    @Test
    @DisplayName("Edge is not mistaken for Chrome; unknown agents are reported as Unknown")
    void edgeAndUnknown() {
        assertClassify(
                "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0",
                "Desktop", "Windows", "Edge");

        for (String ua : new String[] { null, "", "curl/8.5.0" }) {
            ClientInfo info = UserAgentClassifier.classify(ua);
            assertThat(info.device()).isEqualTo(UserAgentClassifier.UNKNOWN);
            assertThat(info.operatingSystem()).isEqualTo(UserAgentClassifier.UNKNOWN);
            assertThat(info.browser()).isEqualTo(UserAgentClassifier.UNKNOWN);
        }
    }

    private static void assertClassify(String userAgent, String device, String os, String browser) {
        ClientInfo info = UserAgentClassifier.classify(userAgent);
        assertThat(info.device()).isEqualTo(device);
        assertThat(info.operatingSystem()).isEqualTo(os);
        assertThat(info.browser()).isEqualTo(browser);
    }
}
