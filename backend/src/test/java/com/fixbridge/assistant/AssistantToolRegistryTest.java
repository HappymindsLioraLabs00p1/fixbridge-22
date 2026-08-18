package com.fixbridge.assistant;

import com.fixbridge.auth.AuthUser;
import com.fixbridge.common.enums.UserRole;
import com.fixbridge.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The gate between a language model and the application.
 *
 * <p>These are not tests of plumbing. The assistant's whole safety story is that a model can only
 * cause what a tool allows, and can only cause a <em>mutating</em> tool with a human's say-so — so
 * the cases that matter are the refusals, not the happy path.
 */
class AssistantToolRegistryTest {

    private final AuthUser user =
            new AuthUser(UUID.randomUUID(), "c@example.test", List.of(UserRole.customer));

    /** A tool that records how many times it actually ran. */
    private static final class SpyTool implements AssistantTool {
        private final String name;
        private final boolean mutating;
        final AtomicInteger runs = new AtomicInteger();

        SpyTool(String name, boolean mutating) {
            this.name = name;
            this.mutating = mutating;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "spy"; }
        @Override public Map<String, Object> parameters() { return Map.of(); }
        @Override public boolean mutating() { return mutating; }
        @Override public Object execute(AuthUser u, Map<String, Object> a) {
            runs.incrementAndGet();
            return "ran";
        }
    }

    // ---- What may not run ----

    @Test
    void aMutatingToolDoesNotRunWithoutConfirmation() {
        SpyTool book = new SpyTool("book_job", true);
        var registry = new AssistantToolRegistry(List.of(book));

        assertThatThrownBy(() -> registry.run(user, "book_job", Map.of(), false))
                .isInstanceOf(ConfirmationRequiredException.class);
        assertThat(book.runs).hasValue(0);
    }

    @Test
    void theRefusalCarriesBackTheArgumentsToReplayVerbatim() {
        // The customer approves a specific action. If the confirmation did not carry the arguments
        // forward, the caller would have to reconstruct them — and could reconstruct them differently
        // from what was shown and agreed to.
        var registry = new AssistantToolRegistry(List.of(new SpyTool("book_job", true)));
        Map<String, Object> args = Map.of("property_id", "abc", "title", "Leaking tap");

        assertThatThrownBy(() -> registry.run(user, "book_job", args, false))
                .isInstanceOfSatisfying(ConfirmationRequiredException.class, e -> {
                    assertThat(e.getToolName()).isEqualTo("book_job");
                    assertThat(e.getArguments()).isEqualTo(args);
                    assertThat(e.getPrompt()).isNotBlank();
                });
    }

    @Test
    void theRefusalStillReadsAsNeedsConfirmationIfItEscapesUnhandled() {
        // It extends ApiException with a 409 precisely so an unhandled path degrades to something
        // truthful rather than a 500.
        var registry = new AssistantToolRegistry(List.of(new SpyTool("book_job", true)));

        assertThatThrownBy(() -> registry.run(user, "book_job", Map.of(), false))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void confirmationIsPerCallAndDoesNotCarryOver() {
        // The danger is a customer confirming one booking and the model treating that as standing
        // permission for whatever it does next.
        SpyTool book = new SpyTool("book_job", true);
        var registry = new AssistantToolRegistry(List.of(book));

        registry.run(user, "book_job", Map.of(), true);
        assertThat(book.runs).hasValue(1);

        assertThatThrownBy(() -> registry.run(user, "book_job", Map.of(), false))
                .isInstanceOf(ConfirmationRequiredException.class);
        assertThat(book.runs).hasValue(1);
    }

    @Test
    void anUnknownToolIsRefused() {
        var registry = new AssistantToolRegistry(List.of(new SpyTool("list_my_jobs", false)));

        assertThatThrownBy(() -> registry.run(user, "transfer_funds", Map.of(), true))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void anUnknownToolCountsAsMutating() {
        // Fail safe: if something asks whether an unrecognised name is dangerous, the answer is yes.
        var registry = new AssistantToolRegistry(List.of(new SpyTool("list_my_jobs", false)));

        assertThat(registry.isMutating("who_knows")).isTrue();
    }

    @Test
    void twoToolsCannotShareAName() {
        // Otherwise the model's choice resolves to whichever bean Spring ordered last.
        assertThatThrownBy(() -> new AssistantToolRegistry(
                List.of(new SpyTool("book_job", true), new SpyTool("book_job", false))))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- What may ----

    @Test
    void aReadOnlyToolRunsWithoutConfirmation() {
        SpyTool list = new SpyTool("list_my_jobs", false);
        var registry = new AssistantToolRegistry(List.of(list));

        assertThat(registry.run(user, "list_my_jobs", Map.of(), false)).isEqualTo("ran");
        assertThat(list.runs).hasValue(1);
    }

    @Test
    void nullArgumentsReachTheToolAsAnEmptyMapNotNull() {
        // Models omit the arguments object entirely for no-argument tools.
        AssistantTool strict = new AssistantTool() {
            @Override public String name() { return "list_my_jobs"; }
            @Override public String description() { return "d"; }
            @Override public Map<String, Object> parameters() { return Map.of(); }
            @Override public boolean mutating() { return false; }
            @Override public Object execute(AuthUser u, Map<String, Object> a) { return a.size(); }
        };

        assertThat(new AssistantToolRegistry(List.of(strict)).run(user, "list_my_jobs", null, false))
                .isEqualTo(0);
    }

    @Test
    void specsExposeEveryToolToTheModel() {
        var registry = new AssistantToolRegistry(
                List.of(new SpyTool("list_my_jobs", false), new SpyTool("book_job", true)));

        assertThat(registry.specs()).hasSize(2);
        assertThat(registry.specs().get(0)).containsKeys("name", "description", "parameters");
    }

    @Test
    void aToolThatForgetsToDeclareItselfIsTreatedAsMutating() {
        // The interface default is `true`, so an author who never thought about it gets the safe side.
        AssistantTool forgetful = new AssistantTool() {
            @Override public String name() { return "does_something"; }
            @Override public String description() { return "d"; }
            @Override public Map<String, Object> parameters() { return Map.of(); }
            @Override public Object execute(AuthUser u, Map<String, Object> a) { return "ran"; }
        };
        var registry = new AssistantToolRegistry(List.of(forgetful));

        assertThat(registry.isMutating("does_something")).isTrue();
        assertThatThrownBy(() -> registry.run(user, "does_something", Map.of(), false))
                .isInstanceOf(ConfirmationRequiredException.class);
    }
}
