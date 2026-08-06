package com.fixbridge.ai;

import com.fixbridge.common.enums.AiUrgency;
import com.fixbridge.support.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StubAiAssessmentClientTest {

    private final StubAiAssessmentClient client = new StubAiAssessmentClient(TestFixtures.props());

    @Test
    void dangerousIssueBlocksDiyAndRequiresProfessional() {
        AssessmentResult r = client.assess("There is a gas smell in the kitchen", List.of());
        assertThat(r.urgency()).isEqualTo(AiUrgency.emergency);
        assertThat(r.safeDiyAllowed()).isFalse();
        assertThat(r.professionalRequired()).isTrue();
        assertThat(r.immediateSafetySteps()).isNotEmpty();
    }

    @Test
    void leakIsClassifiedPlumbingHighAndNotDiy() {
        AssessmentResult r = client.assess("Active water leak under the sink", List.of());
        assertThat(r.category()).isEqualTo("plumbing");
        assertThat(r.urgency()).isEqualTo(AiUrgency.high);
        assertThat(r.safeDiyAllowed()).isFalse();
    }

    @Test
    void minorIssueIsHandymanAndSafeDiy() {
        AssessmentResult r = client.assess("Cabinet door hinge is loose", List.of());
        assertThat(r.category()).isEqualTo("handyman");
        assertThat(r.safeDiyAllowed()).isTrue();
    }

    @Test
    void modelNameComesFromConfiguration() {
        assertThat(client.model()).isEqualTo("gpt-test");
    }
}
