package com.zhiyu.health.controller.b;

import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.service.PrescriptionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/b/prescriptions")
@RequiredArgsConstructor
public class PrescriptionReviewController {
    private final PrescriptionService service;

    public record ReviewInput(@NotBlank String decision, @Size(max = 1000) String reason) {}

    @GetMapping
    public List<PrescriptionService.PrescriptionView> list(
            @RequestParam(required = false) String status, @RequestParam(required = false) String keyword) {
        return service.listForReview(status, keyword);
    }

    @PostMapping("/{id}/review")
    public PrescriptionService.PrescriptionView review(
            @PathVariable long id,
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long reviewerId,
            @Valid @RequestBody ReviewInput input) {
        return service.review(reviewerId, id, input.decision(), input.reason());
    }
}
