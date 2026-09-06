package com.vcampus.server.database;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AcademicCurriculumMigrationTest {
    private static final Path MIGRATION = Path.of(
            "..", "database", "migrations", "014_academic_curriculum_javafx.sql");
    private static final Path SCHEMA = Path.of("..", "database", "schema.sql");

    @Test
    void migrationAndFreshSchemaDefineCurriculumAndRevisionTables() throws Exception {
        String migration = Files.readString(MIGRATION);
        String schema = Files.readString(SCHEMA);

        for (String table : List.of(
                "curriculum_plans",
                "curriculum_plan_courses",
                "course_section_targets",
                "course_section_schedule_revisions")) {
            assertContainsBoth(migration, schema, "CREATE TABLE IF NOT EXISTS " + table);
        }

        assertTrue(migration.contains("ADD COLUMN revision_id"));
        assertTrue(schema.contains("FOREIGN KEY (revision_id, section_id)"));
        assertContainsBoth(migration, schema, "uk_schedule_revision_identity");
        assertContainsBoth(migration, schema, "'SCHEDULE_CHANGED'");
        assertContainsBoth(migration, schema, "'ACADEMIC_SCHEDULE'");
    }

    @Test
    void migrationBackfillsSchedulesBeforeMakingRevisionRequired() throws Exception {
        String migration = Files.readString(MIGRATION);

        int addColumn = migration.indexOf("ADD COLUMN revision_id");
        int createPublishedRevision = migration.indexOf(
                "INSERT INTO course_section_schedule_revisions");
        int backfillSchedules = migration.indexOf("UPDATE class_schedules");
        int makeRequired = migration.indexOf("MODIFY COLUMN revision_id BIGINT NOT NULL");

        assertTrue(addColumn >= 0, "migration must add revision_id");
        assertTrue(createPublishedRevision > addColumn,
                "published revisions must be created after the column exists");
        assertTrue(backfillSchedules > createPublishedRevision,
                "schedule rows must be assigned to published revisions");
        assertTrue(makeRequired > backfillSchedules,
                "revision_id may become NOT NULL only after backfill");
    }

    private void assertContainsBoth(String migration, String schema, String fragment) {
        assertTrue(migration.contains(fragment), "migration missing " + fragment);
        assertTrue(schema.contains(fragment), "fresh schema missing " + fragment);
    }
}
