package com.example.waitlist.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.waitlist.domain.LearnerRecords;
import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * End-to-end through HTTP against the seeded application.properties offering
 * (CS201, capacity 2, prerequisite CS101; alice..dave eligible, erin not).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class EnrollmentControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private LearnerRecords learnerRecords;

    private ResultActions enroll(String learnerId) throws Exception {
        return mvc.perform(post("/api/offerings/CS201/enrollments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"learnerId\":\"" + learnerId + "\"}"));
    }

    @Test
    void fullWorkflowEnrollWaitlistDropPromote() throws Exception {
        enroll("alice").andExpect(status().isCreated()).andExpect(jsonPath("$.outcome").value("ENROLLED"));
        enroll("bob").andExpect(status().isCreated());
        enroll("carol").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("WAITLISTED"))
                .andExpect(jsonPath("$.waitlistPosition").value(1));
        enroll("carol").andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("ALREADY_WAITLISTED"));
        enroll("alice").andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("ALREADY_ENROLLED"));

        mvc.perform(delete("/api/offerings/CS201/enrollments/alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DROPPED"))
                .andExpect(jsonPath("$.promotedLearnerId").value("carol"));
        mvc.perform(delete("/api/offerings/CS201/enrollments/alice"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.outcome").value("NOT_ENROLLED"));

        mvc.perform(get("/api/offerings/CS201"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolled[0]").value("bob"))
                .andExpect(jsonPath("$.enrolled[1]").value("carol"))
                .andExpect(jsonPath("$.waitlist").isEmpty())
                .andExpect(jsonPath("$.seatsAvailable").value(0));
    }

    @Test
    void prerequisiteFailureIs422() throws Exception {
        enroll("erin").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.outcome").value("PREREQUISITE_NOT_MET"));
    }

    @Test
    void unknownLearnerIs404() throws Exception {
        enroll("mallory").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.outcome").value("LEARNER_NOT_FOUND"));
    }

    @Test
    void invalidBodiesAre400() throws Exception {
        enroll("").andExpect(status().isBadRequest()).andExpect(jsonPath("$.outcome").value("INVALID_REQUEST"));
        mvc.perform(post("/api/offerings/CS201/enrollments").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/offerings/CS201/enrollments").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/offerings/CS201/enrollments").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.outcome").value("INVALID_REQUEST"));
    }

    @Test
    void unknownOfferingIs404() throws Exception {
        mvc.perform(post("/api/offerings/NOPE/enrollments")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"learnerId\":\"alice\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.outcome").value("OFFERING_NOT_FOUND"));
        mvc.perform(get("/api/offerings/CS201")).andExpect(jsonPath("$.enrolled").isEmpty());
    }

    @Test
    void droppingWaitlistedLearnerLeavesWaitlist() throws Exception {
        enroll("alice");
        enroll("bob");
        enroll("carol");

        mvc.perform(delete("/api/offerings/CS201/enrollments/carol"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("LEFT_WAITLIST"));
        mvc.perform(get("/api/offerings/CS201"))
                .andExpect(jsonPath("$.waitlist").isEmpty())
                .andExpect(jsonPath("$.seatsAvailable").value(0));
    }

    @Test
    void skipPolicyIsVisibleOverHttp() throws Exception {
        enroll("alice");
        enroll("bob");
        enroll("carol");
        enroll("dave");
        learnerRecords.put("carol", Set.of()); // carol's prerequisite revoked while waiting

        mvc.perform(delete("/api/offerings/CS201/enrollments/alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.promotedLearnerId").value("dave"))
                .andExpect(jsonPath("$.removedIneligible[0]").value("carol"));
    }

    @Test
    void whitespaceLearnerIdInPathIs400() throws Exception {
        // URI.create keeps %20 as-is; the template form would double-encode it to %2520
        mvc.perform(delete(URI.create("/api/offerings/CS201/enrollments/%20alice")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.outcome").value("INVALID_REQUEST"));
    }

    @Test
    void wrongContentTypeIs415WithStandardBody() throws Exception {
        mvc.perform(post("/api/offerings/CS201/enrollments")
                        .contentType(MediaType.TEXT_PLAIN).content("alice"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string("Accept", org.hamcrest.Matchers.containsString("application/json")))
                .andExpect(jsonPath("$.outcome").value("INVALID_REQUEST"));
    }

    @Test
    void unsupportedMethodIs405WithStandardBody() throws Exception {
        mvc.perform(put(
                        "/api/offerings/CS201/enrollments/alice"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("DELETE")))
                .andExpect(jsonPath("$.outcome").value("INVALID_REQUEST"));
    }

    @Test
    void unacceptableAcceptHeaderIs406WithoutBody() throws Exception {
        mvc.perform(get("/api/offerings/CS201").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().string(""));
    }

    @Test
    void prerequisiteMessageListsCodesInStableOrder() throws Exception {
        enroll("erin").andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("[CS101]")));
    }

    @Test
    void getAndDeleteOnUnknownOfferingAre404() throws Exception {
        mvc.perform(get("/api/offerings/NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.outcome").value("OFFERING_NOT_FOUND"));
        mvc.perform(delete("/api/offerings/NOPE/enrollments/alice"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.outcome").value("OFFERING_NOT_FOUND"));
    }
}
