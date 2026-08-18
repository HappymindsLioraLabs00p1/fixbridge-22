package com.fixbridge.assistant;

import com.fixbridge.assistant.tools.ReportIssueTool;
import com.fixbridge.auth.AuthUser;
import com.fixbridge.common.enums.UserRole;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.job.JobService;
import com.fixbridge.job.dto.JobDtos;
import com.fixbridge.property.Property;
import com.fixbridge.property.PropertyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The first tool that writes.
 *
 * <p>What matters here is not that a job gets created — it is what the model is prevented from
 * putting into that job. A language model will happily supply a partner code it invented or a media
 * key it saw in another context, and either would be accepted silently if the tool simply forwarded
 * its arguments.
 */
class ReportIssueToolTest {

    private final JobService jobs = mock(JobService.class);
    private final PropertyRepository properties = mock(PropertyRepository.class);
    private final ReportIssueTool tool = new ReportIssueTool(jobs, properties);

    private final UUID userId = UUID.randomUUID();
    private final AuthUser user = new AuthUser(userId, "c@example.test", List.of(UserRole.customer));

    private UUID propertyId;

    @BeforeEach
    void setUp() {
        propertyId = UUID.randomUUID();
        Property p = mock(Property.class);
        when(p.getId()).thenReturn(propertyId);
        when(p.getOwnerId()).thenReturn(userId);
        when(p.getLabel()).thenReturn("Home");
        when(properties.findById(propertyId)).thenReturn(Optional.of(p));
    }

    private Map<String, Object> args() {
        return Map.of("property_id", propertyId.toString(), "title", "Kitchen tap leaking");
    }

    // ---- What the model may not put in a job ----

    @Test
    void thePartnerCodeIsNeverTakenFromTheModel() {
        // A partner code carries commercial terms. An invented one would apply a discount nobody
        // authorised.
        tool.execute(user, Map.of(
                "property_id", propertyId.toString(),
                "title", "Kitchen tap leaking",
                "partnerCode", "STAFF100",
                "partner_code", "STAFF100"));

        assertThat(captureRequest().partnerCode()).isNull();
    }

    @Test
    void mediaKeysAreNeverTakenFromTheModel() {
        // Storage keys name uploaded files. Accepting one from the model would let it attach a
        // stranger's photograph to this job.
        tool.execute(user, Map.of(
                "property_id", propertyId.toString(),
                "title", "Kitchen tap leaking",
                "mediaKeys", List.of("uploads/someone-elses-photo.jpg")));

        assertThat(captureRequest().mediaKeys()).isEmpty();
    }

    @Test
    void theJobIsFiledForTheAuthenticatedUser() {
        tool.execute(user, args());

        verify(jobs).reportIssue(eq(user), any());
    }

    // ---- Arguments the model gets wrong ----

    @Test
    void aPropertyIdThatIsNotAUuidIsARequestErrorNotACrash() {
        assertThatThrownBy(() -> tool.execute(user, Map.of("property_id", "my house", "title", "Leak")))
                .isInstanceOf(ApiException.class);
        verify(jobs, never()).reportIssue(any(), any());
    }

    @Test
    void aMissingTitleIsRefused() {
        assertThatThrownBy(() -> tool.execute(user, Map.of("property_id", propertyId.toString())))
                .isInstanceOf(ApiException.class);
        verify(jobs, never()).reportIssue(any(), any());
    }

    @Test
    void aMissingDescriptionFallsBackToTheTitleRatherThanFilingAnEmptyJob() {
        tool.execute(user, args());

        assertThat(captureRequest().description()).isEqualTo("Kitchen tap leaking");
    }

    // ---- The confirmation the customer actually sees ----

    @Test
    void theConfirmationNamesTheIssueAndTheProperty() {
        // A prompt the customer cannot check is not consent. "Do you want me to go ahead?" tells them
        // nothing about which address they are approving work on.
        String prompt = tool.confirmationPrompt(user, args());

        assertThat(prompt).contains("Kitchen tap leaking").contains("Home");
    }

    @Test
    void theConfirmationDoesNotNameAPropertyBelongingToSomebodyElse() {
        Property theirs = mock(Property.class);
        when(theirs.getOwnerId()).thenReturn(UUID.randomUUID());
        when(theirs.getLabel()).thenReturn("Their Villa");
        UUID otherId = UUID.randomUUID();
        when(properties.findById(otherId)).thenReturn(Optional.of(theirs));

        String prompt = tool.confirmationPrompt(
                user, Map.of("property_id", otherId.toString(), "title", "Leak"));

        assertThat(prompt).doesNotContain("Their Villa");
    }

    @Test
    void theConfirmationStillReadsWhenTheModelSentNonsense() {
        // This runs before any validation, so it must produce a sentence rather than throw.
        assertThat(tool.confirmationPrompt(user, Map.of("property_id", "nope", "title", "Leak")))
                .isNotBlank();
        assertThat(tool.confirmationPrompt(user, Map.of())).isNotBlank();
    }

    @Test
    void theToolDeclaresItselfMutating() {
        // If this ever flips to false, it runs without the customer ever being asked.
        assertThat(tool.mutating()).isTrue();
    }

    private JobDtos.ReportIssueRequest captureRequest() {
        ArgumentCaptor<JobDtos.ReportIssueRequest> captor =
                ArgumentCaptor.forClass(JobDtos.ReportIssueRequest.class);
        verify(jobs).reportIssue(eq(user), captor.capture());
        return captor.getValue();
    }
}
