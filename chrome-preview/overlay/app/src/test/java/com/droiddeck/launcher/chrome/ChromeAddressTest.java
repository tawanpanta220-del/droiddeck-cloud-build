package com.droiddeck.launcher.chrome;

import static org.junit.Assert.*;
import org.junit.Test;

public class ChromeAddressTest {
    @Test public void bareDomainUsesHttps() {
        assertEquals("https://example.com", ChromeAddress.normalize("example.com"));
    }
    @Test public void httpAndHttpsArePreserved() {
        assertEquals("http://example.com/a?x=1#part", ChromeAddress.normalize("http://example.com/a?x=1#part"));
        assertEquals("HTTPS://example.com/", ChromeAddress.normalize(" HTTPS://example.com/ "));
    }
    @Test public void explicitPortsAndIpv6AreAccepted() {
        assertEquals("https://localhost:8443/path", ChromeAddress.normalize("localhost:8443/path"));
        assertEquals("https://[::1]:8443/", ChromeAddress.normalize("https://[::1]:8443/"));
    }
    @Test public void scriptAndLocalSchemesAreRejected() {
        for (String url : new String[] {"javascript:alert(1)", "file:/etc/passwd", "data:text/plain,test",
                "content://provider/path", "intent://host/", "ftp://example.com/", "chrome://crash"}) {
            assertThrows(url, IllegalArgumentException.class, () -> ChromeAddress.normalize(url));
        }
    }
    @Test public void embeddedCredentialsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ChromeAddress.normalize("https://user:password@example.com/"));
        assertThrows(IllegalArgumentException.class, () -> ChromeAddress.normalize("user@example.com"));
    }
    @Test public void controlsAndInvalidPortsAreRejected() {
        for (String url : new String[] {"https://example.com/a\nb", "https://example.com/\u007f", "https://example.com:65536/", "https://example.com:/"}) {
            assertThrows(url, IllegalArgumentException.class, () -> ChromeAddress.normalize(url));
        }
    }
    @Test public void missingHostAndEmptyAddressAreRejected() {
        for (String url : new String[] {"", " ", "https:///", "https://", "https://exa mple.com"}) {
            assertThrows(url, IllegalArgumentException.class, () -> ChromeAddress.normalize(url));
        }
    }
    @Test public void oversizedAddressIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ChromeAddress.normalize("https://example.com/" + "a".repeat(4096)));
    }
}
