package com.vcampus.client.fx.academic;

import com.vcampus.common.model.CurriculumPlanStatus;

import java.io.IOException;
import java.util.List;

/** Synchronous academic operations. JavaFX callers must invoke these off the FX thread. */
public interface AcademicGateway {
    AcademicData.ReferenceData references() throws IOException;
    AcademicData.CoursePage courses(String keyword, int page) throws IOException;
    long createCourse(AcademicCommands.CourseDraft draft) throws IOException;
    void updateCourse(long courseId, AcademicCommands.CourseDraft draft, boolean enabled) throws IOException;
    AcademicData.CurriculumPage curricula(Long majorId, CurriculumPlanStatus status, int page) throws IOException;
    AcademicData.CurriculumDetail curriculum(long planId) throws IOException;
    long createCurriculum(AcademicCommands.CurriculumDraft draft) throws IOException;
    void updateCurriculum(long planId, AcademicCommands.CurriculumDraft draft) throws IOException;
    long copyCurriculum(long planId, String name, int yearFrom, int yearTo) throws IOException;
    void saveCurriculumCourse(long planId, AcademicCommands.CurriculumCourseDraft draft) throws IOException;
    void removeCurriculumCourse(long planId, long courseId) throws IOException;
    void publishCurriculum(long planId) throws IOException;
    void archiveCurriculum(long planId) throws IOException;
    AcademicData.SectionPage sections(long termId, String keyword, int page) throws IOException;
    long createSection(AcademicCommands.SectionDraft draft) throws IOException;
    List<AcademicData.SectionTarget> sectionTargets(long sectionId) throws IOException;
    void saveSectionTargets(long sectionId, List<AcademicData.SectionTarget> targets) throws IOException;
    AcademicData.ScheduleDraft scheduleDraft(long sectionId) throws IOException;
    AcademicData.ScheduleDraft saveSchedule(AcademicCommands.ScheduleDraftCommand command) throws IOException;
    void publishSchedule(AcademicCommands.SchedulePublishCommand command) throws IOException;
    AcademicData.EnrollmentCatalog enrollmentCatalog(long termId) throws IOException;
    void enroll(long sectionId) throws IOException;
    void drop(long sectionId) throws IOException;
    void switchSection(long fromSectionId, long toSectionId) throws IOException;
    List<AcademicData.ScheduleEntry> studentSchedule(long termId) throws IOException;
    List<AcademicData.ScheduleEntry> teacherSchedule(long termId, Long teacherUserId) throws IOException;
    List<AcademicData.TeachingSection> teacherSections(long termId) throws IOException;
    AcademicData.Roster roster(long sectionId) throws IOException;
    void saveGrade(long sectionId, long enrollmentId, AcademicCommands.GradeDraft draft) throws IOException;
    void publishGrades(long sectionId) throws IOException;
    List<AcademicData.GradeRow> myGrades() throws IOException;
}
