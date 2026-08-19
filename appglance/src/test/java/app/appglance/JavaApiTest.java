package app.appglance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.EnumSet;
import org.junit.Test;

/**
 * The public API as a Java-only app sees it. This file is the assertion: Kotlin default arguments
 * and `kotlin.time.Duration` are both invisible from Java, so a configuration knob that only the
 * Kotlin constructor reaches cannot be set by such an app at all. It compiles as part of the unit
 * test source set, so losing the Java-callable surface fails the build rather than a review.
 */
public class JavaApiTest {

    @Test
    public void everyConfigurationKnobIsReachableFromJava() {
        AppGlance.Configuration full = new AppGlance.Configuration.Builder("glance_live_test")
                .appId("app.example")
                .endpoint(AppGlance.Configuration.DEFAULT_ENDPOINT)
                .appVersion("1.0")
                .flushIntervalSeconds(30)
                .maxBatchSize(50)
                .heartbeatIntervalSeconds(120)
                .sessionTimeoutSeconds(600)
                .isEnabled(true)
                .collectsCountry(false)
                .enabledEnvironments(EnumSet.of(AppEnvironment.PRODUCTION))
                .environment(AppEnvironment.BETA)
                .trackAppLifecycle(false)
                .debug(true)
                .build();
        assertEquals("glance_live_test", full.getApiKey());
        assertEquals("app.example", full.getAppId());
        assertEquals(50, full.getMaxBatchSize());
        assertEquals(AppEnvironment.BETA, full.getEnvironment());
        assertTrue(full.getEnabledEnvironments().contains(AppEnvironment.PRODUCTION));
        assertTrue(full.getDebug());
    }

    @Test
    public void theKeyAloneIsEnough() {
        AppGlance.Configuration minimal = new AppGlance.Configuration.Builder("glance_live_test").build();
        assertEquals(AppGlance.Configuration.DEFAULT_ENDPOINT, minimal.getEndpoint());
        assertTrue(minimal.isEnabled());
    }

    @Test
    public void theReservedNamesAreConstants() {
        assertEquals("heartbeat", Signal.HEARTBEAT);
        assertEquals("$email", UserProperty.EMAIL);
        assertEquals("production", AppEnvironment.PRODUCTION.getWireValue());
    }
}
