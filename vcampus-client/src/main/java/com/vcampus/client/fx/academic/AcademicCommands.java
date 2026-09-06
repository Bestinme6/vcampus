package com.vcampus.client.fx.academic;

import com.vcampus.common.model.CourseRequirementType;
import com.vcampus.common.model.CourseSectionStatus;
import com.vcampus.common.model.ScheduleSlot;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Immutable commands accepted by the JavaFX academic client boundary. */
public final class AcademicCommands {
    private AcademicCommands() {
    }

    public record CourseDraft(String courseCode, String courseName, BigDecimal credits,
                              int totalHours, String description) {
        public CourseDraft {
            courseCode = text(courseCode, "courseCode");
            courseName = text(courseName, "courseName");
            credits = Objects.requireNonNull(credits, "credits");
            if (credits.signum() <= 0 || totalHours < 1) throw new IllegalArgumentException("课程数据无效");
            description = Objects.requireNonNullElse(description, "").trim();
        }
    }

    public record CurriculumDraft(long majorId, String planName, int versionNo,
                                  int yearFrom, int yearTo) {
        public CurriculumDraft {
            planName = text(planName, "planName");
            if (majorId < 1 || versionNo < 1 || yearFrom < 1900 || yearTo < yearFrom) {
                throw new IllegalArgumentException("培养方案数据无效");
            }
        }
    }

    public record CurriculumCourseDraft(long courseId, CourseRequirementType requirementType,
                                        int recommendedTermNumber, boolean alreadyInPlan) {
        public CurriculumCourseDraft {
            if (courseId < 1 || recommendedTermNumber < 1 || recommendedTermNumber > 12) {
                throw new IllegalArgumentException("培养方案课程数据无效");
            }
            requirementType = Objects.requireNonNull(requirementType, "requirementType");
        }

        public CurriculumCourseDraft(long courseId, CourseRequirementType requirementType,
                                     int recommendedTermNumber) {
            this(courseId, requirementType, recommendedTermNumber, false);
        }
    }

    public record SectionDraft(long termId, long courseId, String sectionCode,
                               long teacherUserId, int capacity, CourseSectionStatus status,
                               List<ScheduleSlot> slots, boolean publishSchedule) {
        public SectionDraft {
            sectionCode = text(sectionCode, "sectionCode");
            if (termId < 1 || courseId < 1 || teacherUserId < 1 || capacity < 1) {
                throw new IllegalArgumentException("教学班数据无效");
            }
            status = Objects.requireNonNull(status, "status");
            slots = List.copyOf(Objects.requireNonNull(slots, "slots"));
            if (slots.isEmpty()) throw new IllegalArgumentException("教学班至少需要一个上课时段");
        }
    }

    public record ScheduleDraftCommand(long sectionId, long scheduleRevisionId,
                                       int expectedRevisionNo, List<ScheduleSlot> slots) {
        public ScheduleDraftCommand {
            if (sectionId < 1 || scheduleRevisionId < 1 || expectedRevisionNo < 1) {
                throw new IllegalArgumentException("课表草稿数据无效");
            }
            slots = List.copyOf(Objects.requireNonNull(slots, "slots"));
        }
    }

    public record SchedulePublishCommand(long sectionId, long scheduleRevisionId,
                                         int expectedRevisionNo) {
        public SchedulePublishCommand {
            if (sectionId < 1 || scheduleRevisionId < 1 || expectedRevisionNo < 1) {
                throw new IllegalArgumentException("课表发布数据无效");
            }
        }
    }

    public record GradeDraft(BigDecimal score, String comment, String reason) {
        public GradeDraft {
            score = Objects.requireNonNull(score, "score");
            if (score.signum() < 0 || score.compareTo(new BigDecimal("100")) > 0) {
                throw new IllegalArgumentException("成绩必须在 0—100 之间");
            }
            comment = Objects.requireNonNullElse(comment, "").trim();
            reason = text(reason, "reason");
        }
    }

    private static String text(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isEmpty()) throw new IllegalArgumentException(name);
        return value;
    }
}
