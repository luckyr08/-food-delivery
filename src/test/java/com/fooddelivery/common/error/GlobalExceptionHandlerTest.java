package com.fooddelivery.common.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web-layer slice test: only MVC + the advice load (no DB). */
@WebMvcTest(controllers = GlobalExceptionHandlerTest.ErrorTestController.class)
@Import(GlobalExceptionHandlerTest.ErrorTestController.class)
@WithMockUser
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void notFoundExceptionReturns404ProblemDetail() throws Exception {
        mvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Restaurant 42 not found"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void conflictExceptionReturns409WithItsCode() throws Exception {
        mvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void invalidBodyListsEveryInvalidField() throws Exception {
        mvc.perform(post("/test/body").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[?(@.field=='quantity')].message").exists());
    }

    @Test
    void malformedJsonIsInvalidRequest() throws Exception {
        mvc.perform(post("/test/body").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void wrongPathVariableTypeIsInvalidRequest() throws Exception {
        mvc.perform(get("/test/items/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void requestParamConstraintIsValidated() throws Exception {
        mvc.perform(get("/test/page").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("page"));
    }

    @Test
    void dataIntegrityViolationHidesSqlDetails() throws Exception {
        mvc.perform(get("/test/integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_INTEGRITY_VIOLATION"))
                .andExpect(content().string(not(containsString("Duplicate entry"))));
    }

    @Test
    void optimisticLockFailureIsConcurrentModification() throws Exception {
        mvc.perform(get("/test/optimistic"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void unexpectedExceptionReturnsGeneric500WithoutInternals() throws Exception {
        mvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(content().string(not(containsString("secret-internal-detail"))));
    }

    @Test
    void accessDeniedInsideControllerIs403NotA500() throws Exception {
        mvc.perform(get("/test/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void badCredentialsAre401WithGenericMessage() throws Exception {
        mvc.perform(get("/test/bad-credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));
    }

    @Test
    void unknownUrlReturns404ProblemDetail() throws Exception {
        mvc.perform(get("/test/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    // ---- test-only controller ----

    record TestBody(@NotBlank String name, @Positive int quantity) {
    }

    @RestController
    static class ErrorTestController {

        @GetMapping("/test/not-found")
        void notFound() {
            throw new NotFoundException("Restaurant", 42);
        }

        @GetMapping("/test/conflict")
        void conflict() {
            throw new ConflictException(ErrorCode.CONFLICT, "State does not allow this");
        }

        @PostMapping("/test/body")
        void body(@Valid @RequestBody TestBody body) {
        }

        @GetMapping("/test/items/{id}")
        void item(@PathVariable Long id) {
        }

        @GetMapping("/test/page")
        void page(@RequestParam @Min(0) int page) {
        }

        @GetMapping("/test/integrity")
        void integrity() {
            throw new DataIntegrityViolationException("Duplicate entry 'x' for key 'uk_reviews_order'");
        }

        @GetMapping("/test/optimistic")
        void optimistic() {
            throw new ObjectOptimisticLockingFailureException("Order", 1L);
        }

        @GetMapping("/test/denied")
        void denied() {
            throw new org.springframework.security.access.AccessDeniedException("nope");
        }

        @GetMapping("/test/bad-credentials")
        void badCredentials() {
            throw new org.springframework.security.authentication.BadCredentialsException("Bad credentials");
        }

        @GetMapping("/test/boom")
        void boom() {
            throw new IllegalStateException("secret-internal-detail");
        }
    }
}
