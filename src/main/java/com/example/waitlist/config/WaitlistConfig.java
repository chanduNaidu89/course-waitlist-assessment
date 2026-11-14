package com.example.waitlist.config;

import com.example.waitlist.domain.CourseOffering;
import com.example.waitlist.domain.LearnerRecords;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the beans whose contents come from configuration. {@code EnrollmentService} and
 * {@code PrerequisitePolicy} are component-scanned and receive these via constructor injection.
 */
@Configuration
public class WaitlistConfig {

    @Bean
    LearnerRecords learnerRecords(OfferingProperties properties) {
        LearnerRecords records = new LearnerRecords();
        properties.learners().forEach(records::put);
        return records;
    }

    @Bean
    CourseOffering courseOffering(OfferingProperties properties) {
        return new CourseOffering(
                properties.offeringCode(), properties.capacity(), Set.copyOf(properties.prerequisites()));
    }
}
