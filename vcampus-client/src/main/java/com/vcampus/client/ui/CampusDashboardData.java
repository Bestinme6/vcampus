package com.vcampus.client.ui;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Immutable, UI-neutral snapshot of the signed-in user's campus overview. */
public record CampusDashboardData(
        String displayName,
        String identity,
        String profileLine,
        LocalDate date,
        List<Course> courses,
        List<Task> tasks,
        String loanCount,
        String orderCount,
        String balance,
        List<String> notices) {

    public CampusDashboardData {
        displayName = Objects.requireNonNullElse(displayName, "");
        identity = Objects.requireNonNullElse(identity, "");
        profileLine = Objects.requireNonNullElse(profileLine, "");
        date = Objects.requireNonNull(date, "date");
        courses = List.copyOf(Objects.requireNonNull(courses, "courses"));
        tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks"));
        loanCount = Objects.requireNonNullElse(loanCount, "不可用");
        orderCount = Objects.requireNonNullElse(orderCount, "不可用");
        balance = Objects.requireNonNullElse(balance, "不可用");
        notices = List.copyOf(Objects.requireNonNull(notices, "notices"));
    }

    public record Course(String title, String location, String periods, String detail) {
        public Course {
            title = Objects.requireNonNullElse(title, "");
            location = Objects.requireNonNullElse(location, "");
            periods = Objects.requireNonNullElse(periods, "");
            detail = Objects.requireNonNullElse(detail, "");
        }
    }

    public record Task(String title, String detail, String route, boolean urgent) {
        public Task {
            title = Objects.requireNonNullElse(title, "");
            detail = Objects.requireNonNullElse(detail, "");
            route = Objects.requireNonNullElse(route, "");
        }
    }
}
