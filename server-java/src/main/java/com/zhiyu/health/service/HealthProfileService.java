package com.zhiyu.health.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.HealthProfile;
import com.zhiyu.health.entity.HealthProfileAllergy;
import com.zhiyu.health.mapper.HealthProfileAllergyMapper;
import com.zhiyu.health.mapper.HealthProfileMapper;
import com.zhiyu.health.service.mapping.HealthProfileDtoMapper;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HealthProfileService extends ServiceImpl<HealthProfileMapper, HealthProfile> {

    private final HealthProfileMapper profileMapper;
    private final HealthProfileAllergyMapper allergyMapper;
    private final HealthProfileDtoMapper dtoMapper;

    public List<ProfileView> list(long patientId) {
        return profileMapper
                .selectList(new LambdaQueryWrapper<HealthProfile>()
                        .eq(HealthProfile::getPatientId, patientId)
                        .orderByDesc(HealthProfile::getActive)
                        .orderByAsc(HealthProfile::getId))
                .stream()
                .map(this::toView)
                .toList();
    }

    public ProfileView current(long patientId) {
        HealthProfile profile = profileMapper.selectActive(patientId);
        return profile == null ? null : toView(profile);
    }

    @Transactional
    public ProfileView create(CreateCommand command) {
        profileMapper.clearActive(command.patientId());
        HealthProfile profile = dtoMapper.toEntity(command);
        profileMapper.insert(profile);
        for (String allergen : normalizeAllergies(command.allergies())) {
            HealthProfileAllergy allergy = new HealthProfileAllergy();
            allergy.setHealthProfileId(profile.getId());
            allergy.setAllergen(allergen);
            allergyMapper.insert(allergy);
        }
        return toView(profile);
    }

    @Transactional
    public ProfileView activate(long patientId, long profileId) {
        HealthProfile profile = requireOwned(patientId, profileId);
        profileMapper.clearActive(patientId);
        if (profileMapper.activate(profileId, patientId) != 1) {
            throw new ApiException(409, "健康档案切换失败");
        }
        profile.setActive(true);
        return toView(profile);
    }

    public HealthProfile requireActive(long patientId) {
        HealthProfile profile = profileMapper.selectActive(patientId);
        if (profile == null) {
            throw new ApiException(409, "请先创建健康档案并选择当前服务对象");
        }
        return profile;
    }

    public List<TimelineView> timeline(long patientId, long profileId) {
        requireOwned(patientId, profileId);
        return profileMapper.selectTimeline(patientId, profileId).stream()
                .map(item -> new TimelineView(
                        item.getType(),
                        item.getRecordId(),
                        item.getTitle(),
                        item.getSummary(),
                        item.getOccurredAt() == null
                                ? null
                                : item.getOccurredAt().toString(),
                        item.getDisclaimer()))
                .toList();
    }

    @Transactional
    public ProfileView replaceAllergies(long patientId, long profileId, List<String> allergies) {
        HealthProfile profile = requireOwned(patientId, profileId);
        allergyMapper.delete(
                new LambdaQueryWrapper<HealthProfileAllergy>().eq(HealthProfileAllergy::getHealthProfileId, profileId));
        for (String allergen : normalizeAllergies(allergies)) {
            HealthProfileAllergy allergy = new HealthProfileAllergy();
            allergy.setHealthProfileId(profileId);
            allergy.setAllergen(allergen);
            allergyMapper.insert(allergy);
        }
        return toView(profile);
    }

    private HealthProfile requireOwned(long patientId, long profileId) {
        HealthProfile profile = profileMapper.selectOwned(profileId, patientId);
        if (profile == null) {
            throw new ApiException(404, "健康档案不存在");
        }
        return profile;
    }

    private ProfileView toView(HealthProfile profile) {
        return dtoMapper.toView(profile, allergyMapper.selectAllergens(profile.getId()));
    }

    private List<String> normalizeAllergies(List<String> allergies) {
        if (allergies == null) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String allergy : allergies) {
            if (allergy != null && !allergy.isBlank()) {
                normalized.add(allergy.trim());
            }
        }
        return List.copyOf(normalized);
    }

    public record CreateCommand(
            long patientId,
            String displayName,
            String gender,
            LocalDate birthDate,
            String relationship,
            List<String> allergies) {}

    public record ProfileView(
            Long id,
            @JsonProperty("display_name") String displayName,
            String gender,
            @JsonProperty("birth_date") LocalDate birthDate,
            String relationship,
            boolean active,
            List<String> allergies) {}

    public record TimelineView(
            String type,
            @JsonProperty("record_id") Long recordId,
            String title,
            String summary,
            @JsonProperty("occurred_at") String occurredAt,
            String disclaimer) {}
}
