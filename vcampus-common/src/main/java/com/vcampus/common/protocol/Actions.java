package com.vcampus.common.protocol;

public final class Actions {
    public static final String SYSTEM_PING = "system.ping";
    public static final String AUTH_LOGIN = "auth.login";
    public static final String AUTH_LOGOUT = "auth.logout";
    public static final String AUTH_SESSION = "auth.session";
    public static final String AUTH_CHANGE_PASSWORD = "auth.changePassword";

    public static final String ACCOUNT_SEARCH = "account.search";
    public static final String ACCOUNT_REFERENCE_DATA = "account.referenceData";
    public static final String ACCOUNT_CREATE = "account.create";
    public static final String ACCOUNT_UPDATE_ROLES = "account.updateRoles";
    public static final String ACCOUNT_SET_ENABLED = "account.setEnabled";
    public static final String ACCOUNT_RESET_PASSWORD = "account.resetPassword";

    public static final String STUDENT_GET_SELF = "student.getSelf";
    public static final String STUDENT_GET = "student.get";
    public static final String STUDENT_SEARCH = "student.search";
    public static final String STUDENT_UPDATE = "student.update";
    public static final String STUDENT_UPDATE_CONTACT = "student.updateContact";
    public static final String STUDENT_CHANGE_STATUS = "student.changeStatus";
    public static final String STUDENT_STATUS_HISTORY = "student.statusHistory";
    public static final String STUDENT_REFERENCE_DATA = "student.referenceData";

    public static final String TEACHER_PROFILE_GET_SELF = "profile.teacher.getSelf";
    public static final String TEACHER_PROFILE_UPDATE_CONTACT = "profile.teacher.updateContact";

    public static final String ACADEMIC_REFERENCE_DATA = "academic.referenceData";
    public static final String ACADEMIC_COURSE_SEARCH = "academic.course.search";
    public static final String ACADEMIC_COURSE_CREATE = "academic.course.create";
    public static final String ACADEMIC_COURSE_UPDATE = "academic.course.update";
    public static final String ACADEMIC_SECTION_SEARCH = "academic.section.search";
    public static final String ACADEMIC_SECTION_CREATE = "academic.section.create";
    public static final String ACADEMIC_SECTION_SET_STATUS = "academic.section.setStatus";
    public static final String ACADEMIC_ENROLLMENT_AVAILABLE = "academic.enrollment.available";
    public static final String ACADEMIC_ENROLLMENT_ENROLL = "academic.enrollment.enroll";
    public static final String ACADEMIC_ENROLLMENT_DROP = "academic.enrollment.drop";
    public static final String ACADEMIC_SCHEDULE_MY = "academic.schedule.my";
    public static final String ACADEMIC_SCHEDULE_TEACHER = "academic.schedule.teacher";
    public static final String ACADEMIC_TEACHER_SECTIONS = "academic.teacher.sections";
    public static final String ACADEMIC_SECTION_ROSTER = "academic.section.roster";
    public static final String ACADEMIC_GRADE_SAVE = "academic.grade.save";
    public static final String ACADEMIC_GRADE_PUBLISH = "academic.grade.publish";
    public static final String ACADEMIC_GRADE_MY = "academic.grade.my";

    public static final String NOTIFICATION_SEARCH = "notification.search";
    public static final String NOTIFICATION_GET = "notification.get";
    public static final String NOTIFICATION_UNREAD_COUNT = "notification.unreadCount";
    public static final String NOTIFICATION_MARK_READ = "notification.markRead";
    public static final String NOTIFICATION_MARK_ALL_READ = "notification.markAllRead";

    public static final String LIBRARY_CATALOG_SEARCH = "library.catalog.search";
    public static final String LIBRARY_CATALOG_GET = "library.catalog.get";
    public static final String LIBRARY_LOAN_MY = "library.loan.my";
    public static final String LIBRARY_LOAN_BORROW = "library.loan.borrow";
    public static final String LIBRARY_LOAN_RETURN = "library.loan.return";
    public static final String LIBRARY_LOAN_RENEW = "library.loan.renew";
    public static final String LIBRARY_ADMIN_BOOK_CREATE = "library.admin.book.create";
    public static final String LIBRARY_ADMIN_BOOK_UPDATE = "library.admin.book.update";
    public static final String LIBRARY_ADMIN_BOOK_SET_ENABLED = "library.admin.book.set-enabled";
    public static final String LIBRARY_ADMIN_COPY_SEARCH = "library.admin.copy.search";
    public static final String LIBRARY_ADMIN_COPY_CREATE = "library.admin.copy.create";
    public static final String LIBRARY_ADMIN_COPY_SET_STATUS = "library.admin.copy.set-status";
    public static final String LIBRARY_ADMIN_LOAN_SEARCH = "library.admin.loan.search";
    public static final String LIBRARY_ADMIN_CIRCULATION_PREVIEW = "library.admin.circulation.preview";
    public static final String LIBRARY_ADMIN_LOAN_BORROW = "library.admin.loan.borrow";
    public static final String LIBRARY_ADMIN_LOAN_RETURN = "library.admin.loan.return";

    public static final String FORUM_SECTION_LIST = "forum.section.list";
    public static final String FORUM_POST_SEARCH = "forum.post.search";
    public static final String FORUM_POST_GET = "forum.post.get";
    public static final String FORUM_POST_CREATE = "forum.post.create";
    public static final String FORUM_POST_DELETE = "forum.post.delete";
    public static final String FORUM_COMMENT_LIST = "forum.comment.list";
    public static final String FORUM_COMMENT_CREATE = "forum.comment.create";
    public static final String FORUM_COMMENT_DELETE = "forum.comment.delete";
    public static final String FORUM_ADMIN_SECTION_SAVE = "forum.admin.section.save";
    public static final String FORUM_ADMIN_SECTION_SET_ENABLED =
            "forum.admin.section.setEnabled";
    public static final String FORUM_ADMIN_CONTENT_SEARCH = "forum.admin.content.search";
    public static final String FORUM_ADMIN_POST_MODERATE = "forum.admin.post.moderate";
    public static final String FORUM_ADMIN_COMMENT_MODERATE = "forum.admin.comment.moderate";
    public static final String FORUM_ADMIN_LOG_SEARCH = "forum.admin.log.search";

    private Actions() {
    }
}
