package com.fixbridge.notification;

import com.fixbridge.contractor.ContractorRepository;
import com.fixbridge.support.TestFixtures;
import com.fixbridge.user.Profile;
import com.fixbridge.user.ProfileRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceTest {

    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final ProfileRepository profiles = mock(ProfileRepository.class);
    private final ContractorRepository contractors = mock(ContractorRepository.class);
    private final SmsSender sms = mock(SmsSender.class);
    private final EmailSender email = mock(EmailSender.class);

    private NotificationService service() {
        return new NotificationService(notifications, profiles, contractors, sms, email, TestFixtures.props());
    }

    @Test
    void proposalSent_sendsBothChannelsAndRecordsThem() {
        UUID customerId = UUID.randomUUID();
        Profile p = new Profile();
        p.setEmail("customer@example.com");
        p.setPhone("+15551234567");
        when(profiles.findById(customerId)).thenReturn(Optional.of(p));
        when(sms.sendSms(anyString(), anyString())).thenReturn(true);
        when(email.sendEmail(anyString(), anyString(), anyString())).thenReturn(true);

        service().proposalSent(customerId, UUID.randomUUID(), 90_888);

        verify(sms).sendSms(eq("+15551234567"), anyString());
        verify(email).sendEmail(eq("customer@example.com"), anyString(), anyString());
        verify(notifications, times(2)).save(any()); // one row per channel
    }

    @Test
    void payoutReleased_smsOnlyWhenPhoneMissingSkipsSend() {
        UUID contractorId = UUID.randomUUID();
        when(contractors.findById(contractorId)).thenReturn(Optional.empty()); // no recipient resolved

        service().payoutReleased(contractorId, UUID.randomUUID(), 53_000);

        verify(sms, never()).sendSms(anyString(), anyString());
        verify(notifications, never()).save(any());
    }
}
