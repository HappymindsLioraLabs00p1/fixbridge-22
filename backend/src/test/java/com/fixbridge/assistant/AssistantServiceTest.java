package com.fixbridge.assistant;

import com.fixbridge.assistant.dto.AssistantDtos;
import com.fixbridge.auth.AuthUser;
import com.fixbridge.common.enums.UserRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One turn of the assistant.
 *
 * <p>The behaviour that matters is what happens around a mutating tool: the customer must be asked
 * before anything is written, must be told what they are agreeing to, and their approval must apply
 * to that action rather than to the next one.
 */
class AssistantServiceTest {

    private final AuthUser user =
            new AuthUser(UUID.randomUUID(), "c@example.test", List.of(UserRole.customer));

    private static final class SpyTool implements AssistantTool {
        private final String name;
        private final boolean mutating;
        final AtomicInteger runs = new AtomicInteger();
        Map<String, Object> lastArgs;

        SpyTool(String name, boolean mutating) {
            this.name = name;
            this.mutating = mutating;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "spy"; }
        @Override public Map<String, Object> parameters() { return Map.of(); }
        @Override public boolean mutating() { return mutating; }
        @Override public String confirmationPrompt(AuthUser u, Map<String, Object> a) {
            return "Shall I file \"" + a.get("title") + "\"?";
        }
        @Override public Object execute(AuthUser u, Map<String, Object> a) {
            runs.incrementAndGet();
            lastArgs = a;
            return "filed";
        }
    }

    /** An engine that always picks the same tool, so the service is what is under test. */
    private static AssistantEngine engineChoosing(String tool, Map<String, Object> args) {
        return new AssistantEngine() {
            @Override public Decision decide(AuthUser u, String m) { return Decision.use(tool, args); }
            @Override public String narrate(String t, Object r) { return "narrated:" + r; }
            @Override public String name() { return "test"; }
        };
    }

    private AssistantDtos.MessageRequest say(String text) {
        return new AssistantDtos.MessageRequest(text, null, null, false);
    }

    // ---- Asking before acting ----

    @Test
    void aMutatingToolIsProposedNotRunAndTheReplyCarriesThePendingAction() {
        SpyTool file = new SpyTool("report_issue", true);
        Map<String, Object> args = Map.of("title", "Leaking tap", "property_id", "p1");
        var service = new AssistantService(
                engineChoosing("report_issue", args), new AssistantToolRegistry(List.of(file)));

        var reply = service.handle(user, say("my tap is leaking"));

        assertThat(file.runs).hasValue(0);
        assertThat(reply.pending()).isNotNull();
        assertThat(reply.pending().tool()).isEqualTo("report_issue");
        assertThat(reply.pending().arguments()).isEqualTo(args);
        assertThat(reply.reply()).contains("Leaking tap");
    }

    @Test
    void confirmingRunsExactlyWhatWasProposed() {
        SpyTool file = new SpyTool("report_issue", true);
        Map<String, Object> args = Map.of("title", "Leaking tap", "property_id", "p1");
        var service = new AssistantService(
                engineChoosing("report_issue", args), new AssistantToolRegistry(List.of(file)));

        var proposal = service.handle(user, say("my tap is leaking"));
        var done = service.handle(user, new AssistantDtos.MessageRequest(
                "yes", proposal.pending().tool(), proposal.pending().arguments(), true));

        assertThat(file.runs).hasValue(1);
        assertThat(file.lastArgs).isEqualTo(args);
        assertThat(done.pending()).isNull();
        assertThat(done.reply()).isEqualTo("narrated:filed");
    }

    @Test
    void sayingYesWithoutAPendingActionDoesNotRunAnything() {
        // "yes" on its own must not be treated as approval of whatever the engine picks next.
        SpyTool file = new SpyTool("report_issue", true);
        var service = new AssistantService(
                engineChoosing("report_issue", Map.of("title", "Leaking tap")),
                new AssistantToolRegistry(List.of(file)));

        var reply = service.handle(user, new AssistantDtos.MessageRequest("yes", null, null, true));

        assertThat(file.runs).hasValue(0);
        assertThat(reply.pending()).isNotNull();
    }

    // ---- Reading needs no permission ----

    @Test
    void aReadOnlyToolRunsAndIsNarratedWithoutAnyPrompt() {
        SpyTool list = new SpyTool("list_my_jobs", false);
        var service = new AssistantService(
                engineChoosing("list_my_jobs", Map.of()), new AssistantToolRegistry(List.of(list)));

        var reply = service.handle(user, say("what are my jobs"));

        assertThat(list.runs).hasValue(1);
        assertThat(reply.pending()).isNull();
        assertThat(reply.toolsUsed()).containsExactly("list_my_jobs");
    }

    @Test
    void aPlainAnswerUsesNoToolsAtAll() {
        AssistantEngine chatty = new AssistantEngine() {
            @Override public Decision decide(AuthUser u, String m) { return Decision.say("Hello."); }
            @Override public String narrate(String t, Object r) { return "unused"; }
            @Override public String name() { return "test"; }
        };
        var service = new AssistantService(chatty, new AssistantToolRegistry(List.of()));

        var reply = service.handle(user, say("hi"));

        assertThat(reply.reply()).isEqualTo("Hello.");
        assertThat(reply.toolsUsed()).isEmpty();
        assertThat(reply.pending()).isNull();
    }
}
