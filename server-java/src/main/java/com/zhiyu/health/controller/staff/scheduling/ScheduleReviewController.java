package com.zhiyu.health.controller.staff.scheduling;

import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.entity.scheduling.ScheduleRequest;
import com.zhiyu.health.service.scheduling.ScheduleRequestService;
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

/**
 * 管理员排班审核：路径在 /api/b/schedule-requests/**，受 AdminInterceptor 保护（仅 admin）。
 * 审核通过后落盘为 schedules 行，C 端即可见；驳回需填原因。
 */
@RestController
@RequestMapping("/api/b/schedule-requests")
@RequiredArgsConstructor
public class ScheduleReviewController {

    private final ScheduleRequestService scheduleRequestService;

    public record ReviewInput(@NotBlank String decision, @Size(max = 500) String reason) {}

    /** 审核列表：status 默认 PENDING，可传 approved/rejected 查历史。 */
    @GetMapping
    public List<ScheduleRequest> list(@RequestParam(required = false) String status) {
        return scheduleRequestService.listForReview(status);
    }

    @PostMapping("/{id}/review")
    public ScheduleRequest review(
            @PathVariable long id,
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long reviewerId,
            @Valid @RequestBody ReviewInput input) {
        return scheduleRequestService.review(reviewerId, id, input.decision(), input.reason());
    }
}
