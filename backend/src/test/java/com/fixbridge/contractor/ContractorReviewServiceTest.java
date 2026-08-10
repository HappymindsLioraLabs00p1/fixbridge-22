package com.fixbridge.contractor;

import com.fixbridge.common.enums.JobStatus;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.job.Job;
import com.fixbridge.job.JobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * These test the refusals rather than the happy path. A rating feeds contractor ranking, so the
 * interesting question is what a review cannot be: someone else's job, an unfinished job, or the
 * same job twice.
 */
class ContractorReviewServiceTest {

    private final UUID customer = UUID.randomUUID();
    private final UUID contractor = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    private final ContractorReviewRepository reviews = mock(ContractorReviewRepository.class);
    private final JobRepository jobs = mock(JobRepository.class);
    private final ContractorReviewService service = new ContractorReviewService(reviews, jobs);

    private Job job(UUID owner, UUID assigned, JobStatus status) {
        Job j = new Job();
        j.setId(jobId);
        j.setCustomerId(owner);
        j.setAssignedContractorId(assigned);
        j.setStatus(status);
        when(jobs.findById(jobId)).thenReturn(Optional.of(j));
        return j;
    }

    @Test
    void aCompletedJobCanBeReviewed() {
        job(customer, contractor, JobStatus.closed);
        when(reviews.existsByJobIdAndCustomerId(jobId, customer)).thenReturn(false);
        when(reviews.save(any())).thenAnswer(i -> i.getArgument(0));

        var view = service.submit(customer, jobId, 5, "  Excellent work  ");

        assertThat(view.rating()).isEqualTo(5);
        assertThat(view.contractorId()).isEqualTo(contractor);
        assertThat(view.comment()).isEqualTo("Excellent work");   // trimmed
        verify(reviews).save(any(ContractorReview.class));
    }

    @Test
    void theContractorComesFromTheJobNotTheCaller() {
        job(customer, contractor, JobStatus.paid_out);
        when(reviews.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(service.submit(customer, jobId, 4, null).contractorId()).isEqualTo(contractor);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 6, 99})
    void aRatingOutsideOneToFiveIsRejected(int rating) {
        assertThatThrownBy(() -> service.submit(customer, jobId, rating, null))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(reviews);
    }

    @Test
    void aCustomerCannotReviewSomebodyElsesJob() {
        job(UUID.randomUUID(), contractor, JobStatus.closed);

        assertThatThrownBy(() -> service.submit(customer, jobId, 5, null))
                .isInstanceOf(ApiException.class);
        verify(reviews, never()).save(any());
    }

    @Test
    void aJobWithNoContractorHasNobodyToReview() {
        job(customer, null, JobStatus.closed);

        assertThatThrownBy(() -> service.submit(customer, jobId, 5, null))
                .isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"draft", "scheduled", "work_started",
            "awaiting_contractor", "canceled"})
    void unfinishedWorkCannotBeRated(JobStatus status) {
        job(customer, contractor, status);

        assertThatThrownBy(() -> service.submit(customer, jobId, 5, null))
                .isInstanceOf(ApiException.class);
        verify(reviews, never()).save(any());
    }

    @Test
    void theSameJobCannotBeReviewedTwice() {
        job(customer, contractor, JobStatus.closed);
        when(reviews.existsByJobIdAndCustomerId(jobId, customer)).thenReturn(true);

        assertThatThrownBy(() -> service.submit(customer, jobId, 5, null))
                .isInstanceOf(ApiException.class);
        verify(reviews, never()).save(any());
    }

    @Test
    void anEmptyCommentIsStoredAsNullRatherThanBlank() {
        job(customer, contractor, JobStatus.closed);
        when(reviews.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(service.submit(customer, jobId, 3, "   ").comment()).isNull();
    }

    @Test
    void eligibilityIsFalseOnceAReviewExists() {
        job(customer, contractor, JobStatus.closed);
        when(reviews.existsByJobIdAndCustomerId(jobId, customer)).thenReturn(true);

        assertThat(service.canReview(customer, jobId)).isFalse();
    }

    @Test
    void eligibilityIsTrueForAnUnreviewedCompletedJob() {
        job(customer, contractor, JobStatus.work_completed);
        when(reviews.existsByJobIdAndCustomerId(jobId, customer)).thenReturn(false);

        assertThat(service.canReview(customer, jobId)).isTrue();
    }

    @Test
    void eligibilityIsFalseForAJobTheCustomerDoesNotOwn() {
        job(UUID.randomUUID(), contractor, JobStatus.closed);

        assertThat(service.canReview(customer, jobId)).isFalse();
    }
}
