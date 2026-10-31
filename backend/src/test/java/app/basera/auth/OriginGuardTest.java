package app.basera.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OriginGuardTest {

    @Test
    void requestsWithoutOriginAreAllowed() {
        assertThat(OriginGuard.allowed(null, "gec.basera.app")).isTrue();
        assertThat(OriginGuard.allowed("", "gec.basera.app")).isTrue();
    }

    @Test
    void sameHostAndPortMatches() {
        assertThat(OriginGuard.allowed("http://gec-jabalpur.localhost:8081", "gec-jabalpur.localhost:8081")).isTrue();
        assertThat(OriginGuard.allowed("http://localhost:5173", "localhost:5173")).isTrue();
    }

    @Test
    void defaultPortsAreImpliedOnBothSides() {
        assertThat(OriginGuard.allowed("https://gec.basera.app", "gec.basera.app")).isTrue();
        assertThat(OriginGuard.allowed("https://gec.basera.app", "gec.basera.app:443")).isTrue();
        assertThat(OriginGuard.allowed("https://gec.basera.app:443", "gec.basera.app")).isTrue();
        assertThat(OriginGuard.allowed("http://gec.basera.app", "gec.basera.app")).isTrue();
    }

    @Test
    void aDifferentHostIsRejected() {
        assertThat(OriginGuard.allowed("https://evil.example", "gec.basera.app")).isFalse();
        assertThat(OriginGuard.allowed("https://gec-b.basera.app", "gec-a.basera.app")).isFalse();   // another college is another origin
        assertThat(OriginGuard.allowed("https://gec.basera.app.evil.example", "gec.basera.app")).isFalse();
    }

    @Test
    void aDifferentPortOnTheSameHostIsRejected() {
        assertThat(OriginGuard.allowed("http://localhost:3000", "localhost:5173")).isFalse();   // cookies ignore ports, so ports must match
        assertThat(OriginGuard.allowed("http://localhost:8081", "localhost")).isFalse();        // the exact bug nginx's $host caused
    }

    @Test
    void malformedValuesAreRejected() {
        assertThat(OriginGuard.allowed("not a url", "gec.basera.app")).isFalse();
        assertThat(OriginGuard.allowed("null", "gec.basera.app")).isFalse();
        assertThat(OriginGuard.allowed("https://gec.basera.app", null)).isFalse();
        assertThat(OriginGuard.allowed("https://gec.basera.app", "gec.basera.app:abc")).isFalse();
    }
}
