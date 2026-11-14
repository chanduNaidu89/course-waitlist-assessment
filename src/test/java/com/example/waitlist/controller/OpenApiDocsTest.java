package com.example.waitlist.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** The OpenAPI spec and Swagger UI are served and describe the real endpoints. */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsTest {

    private static final String ENROLLMENTS = "$.paths['/api/offerings/{offeringCode}/enrollments']";
    private static final String DROP = "$.paths['/api/offerings/{offeringCode}/enrollments/{learnerId}']";

    @Autowired
    private MockMvc mvc;

    @Test
    void specDescribesAllEndpointsAndOutcomes() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Course Waitlist API"))
                .andExpect(jsonPath("$.info.description", containsString("CS201")))
                .andExpect(jsonPath("$.paths['/api/offerings/{offeringCode}'].get.responses['200']").exists())
                .andExpect(jsonPath(ENROLLMENTS + ".post.responses['201']").exists())
                .andExpect(jsonPath(ENROLLMENTS + ".post.responses['202']").exists())
                .andExpect(jsonPath(ENROLLMENTS + ".post.responses['422']").exists())
                .andExpect(jsonPath(DROP + ".delete.responses['200']").exists())
                .andExpect(jsonPath(DROP + ".delete.responses['404']").exists())
                .andExpect(jsonPath("$.components.schemas.EnrollResult.properties.outcome.enum",
                        org.hamcrest.Matchers.hasItem("PREREQUISITE_NOT_MET")))
                .andExpect(jsonPath("$.components.schemas.ApiError").exists());
        mvc.perform(get("/v3/api-docs.yaml"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("title: Course Waitlist API")));
    }

    @Test
    void swaggerUiIsServed() throws Exception {
        mvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Swagger UI")));
    }
}
