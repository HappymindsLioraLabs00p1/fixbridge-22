package com.fixbridge.storage;

import com.fixbridge.config.FixBridgeProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The parts of the Supabase adapter that can go wrong without a network call: refusing to start
 * half-configured, and turning the relative signed paths Supabase returns into URLs a browser can
 * actually fetch.
 */
class SupabaseStorageServiceTest {

    private FixBridgeProperties props(String url, String key) {
        var supabase = new FixBridgeProperties.Supabase(url, key);
        var storage = new FixBridgeProperties.Storage(
                "repairs", 15, "http://localhost:8080", false, "supabase", supabase);
        // Positional: brand, security, ai, aiService, stripe, storage, twilio, resend,
        // notifications, billing.
        return new FixBridgeProperties(null, null, null, null, null, storage,
                null, null, null, null);
    }

    @Test
    void aMissingUrlFailsAtStartupRatherThanOnFirstUpload() {
        // Discovering this when a customer uploads a repair photo is the worst possible time.
        assertThatThrownBy(() -> new SupabaseStorageService(props("", "key")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPABASE_URL");
    }

    @Test
    void aMissingServiceKeyFailsAtStartup() {
        assertThatThrownBy(() -> new SupabaseStorageService(props("https://x.supabase.co", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPABASE_SERVICE_KEY");
        // The message names the variable, never its value.
    }

    @Test
    void aTrailingSlashOnTheProjectUrlDoesNotProduceADoubleSlash() {
        var service = new SupabaseStorageService(props("https://x.supabase.co/", "key"));
        assertThat(service.absoluteForTest("/object/sign/repairs/a.jpg"))
                .isEqualTo("https://x.supabase.co/storage/v1/object/sign/repairs/a.jpg");
    }

    @Test
    void aRelativeSignedPathBecomesAbsolute() {
        var service = new SupabaseStorageService(props("https://x.supabase.co", "key"));
        assertThat(service.absoluteForTest("object/sign/repairs/a.jpg"))
                .startsWith("https://x.supabase.co/storage/v1/");
    }

    @Test
    void anAlreadyPrefixedPathDoesNotGainASecondPrefix() {
        var service = new SupabaseStorageService(props("https://x.supabase.co", "key"));
        assertThat(service.absoluteForTest("/storage/v1/object/sign/repairs/a.jpg"))
                .isEqualTo("https://x.supabase.co/storage/v1/object/sign/repairs/a.jpg");
    }

    @Test
    void anAbsoluteUrlIsLeftAlone() {
        var service = new SupabaseStorageService(props("https://x.supabase.co", "key"));
        assertThat(service.absoluteForTest("https://cdn.example.com/a.jpg"))
                .isEqualTo("https://cdn.example.com/a.jpg");
    }
}
