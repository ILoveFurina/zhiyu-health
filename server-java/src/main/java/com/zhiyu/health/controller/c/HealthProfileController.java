package com.zhiyu.health.controller.c;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.controller.c.mapping.HealthProfileInputMapper;
import com.zhiyu.health.service.HealthProfileService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** C 端健康档案入口，只做校验、患者身份装配和 DTO 映射。 */
@RestController
@RequestMapping("/api/c/health-profiles")
@RequiredArgsConstructor
public class HealthProfileController {

    private final HealthProfileService service;
    private final HealthProfileInputMapper inputMapper;

    public record CreateInput(
            @JsonProperty("display_name") @NotBlank @Size(max = 50) String displayName,
            @NotBlank @Size(max = 10) String gender,
            @JsonProperty("birth_date") @NotNull LocalDate birthDate,
            @NotBlank @Size(max = 20) String relationship,
            @Size(max = 30) List<@NotBlank @Size(max = 100) String> allergies) {}

    public record CurrentProfileResponse(HealthProfileService.ProfileView profile) {}

    public record AllergiesInput(@NotNull @Size(max = 30) List<@NotBlank @Size(max = 100) String> allergies) {}

    @GetMapping
    public List<HealthProfileService.ProfileView> list(@RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId) {
        return service.list(patientId);
    }

    @GetMapping("/current")
    public CurrentProfileResponse current(@RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId) {
        return new CurrentProfileResponse(service.current(patientId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HealthProfileService.ProfileView create(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId, @Valid @RequestBody CreateInput input) {
        return service.create(inputMapper.toCommand(patientId, input));
    }

    @PostMapping("/{profileId}/activate")
    public HealthProfileService.ProfileView activate(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId, @PathVariable long profileId) {
        return service.activate(patientId, profileId);
    }

    @GetMapping("/{profileId}/timeline")
    public List<HealthProfileService.TimelineView> timeline(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId, @PathVariable long profileId) {
        return service.timeline(patientId, profileId);
    }

    @GetMapping("/{profileId}/overview")
    public HealthProfileService.OverviewView overview(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId, @PathVariable long profileId) {
        return service.overview(patientId, profileId);
    }

    @GetMapping("/{profileId}/observations")
    public HealthProfileService.MetricObservationsView observations(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId,
            @PathVariable long profileId,
            @RequestParam("metric_code") String metricCode) {
        return service.metricObservations(patientId, profileId, metricCode);
    }

    @PutMapping("/{profileId}/allergies")
    public HealthProfileService.ProfileView replaceAllergies(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId,
            @PathVariable long profileId,
            @Valid @RequestBody AllergiesInput input) {
        return service.replaceAllergies(patientId, profileId, input.allergies());
    }
}
