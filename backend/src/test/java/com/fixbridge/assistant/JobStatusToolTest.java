package com.fixbridge.assistant;

import com.fixbridge.assistant.tools.JobStatusTool;
import com.fixbridge.auth.AuthUser;
import com.fixbridge.common.enums.UserRole;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.job.JobService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The one read-only tool that takes an id from the model.
 *
 * <p>That makes it the place where a hallucinated or borrowed job reference could leak another
 * customer's repair history, so the point of these tests is that identity comes from the session and
 * never from the arguments — and that the tool degrades to a 400 rather than a 500 when the model
 * passes prose, which it will.
 */
class JobStatusToolTest {

    private final JobService jobs = mock(JobService.class);
    private final JobStatusTool tool = new JobStatusTool(jobs);

    private final AuthUser user =
            new AuthUser(UUID.randomUUID(), "c@example.test", List.of(UserRole.customer));

    @Test
    void theLookupIsScopedToTheAuthenticatedUser() {
        UUID jobId = UUID.randomUUID();

        tool.execute(user, Map.of("job_id", jobId.toString()));

        // Ownership is enforced by getForCustomer. Going anywhere near a plain repository lookup here
        // would hand back whatever row the id names, whoever it belongs to.
        verify(jobs).getForCustomer(user, jobId);
    }

    @Test
    void aJobIdThatIsNotAUuidIsARequestErrorNotACrash() {
        assertThatThrownBy(() -> tool.execute(user, Map.of("job_id", "the kitchen one")))
                .isInstanceOf(ApiException.class);
        verify(jobs, never()).getForCustomer(any(), any());
    }

    @Test
    void aMissingJobIdIsARequestError() {
        assertThatThrownBy(() -> tool.execute(user, Map.of()))
                .isInstanceOf(ApiException.class);
        verify(jobs, never()).getForCustomer(any(), any());
    }

    @Test
    void aBlankJobIdIsARequestError() {
        assertThatThrownBy(() -> tool.execute(user, Map.of("job_id", "   ")))
                .isInstanceOf(ApiException.class);
        verify(jobs, never()).getForCustomer(any(), any());
    }

    @Test
    void aNullJobIdIsARequestError() {
        Map<String, Object> args = new HashMap<>();
        args.put("job_id", null);

        assertThatThrownBy(() -> tool.execute(user, args))
                .isInstanceOf(ApiException.class);
        verify(jobs, never()).getForCustomer(any(), any());
    }

    @Test
    void theToolIsReadOnly() {
        // If this ever flips, the registry stops asking for confirmation before running it.
        org.assertj.core.api.Assertions.assertThat(tool.mutating()).isFalse();
    }
}
