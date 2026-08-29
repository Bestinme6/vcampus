package com.vcampus.server.service;

import com.vcampus.common.model.ForcedPasswordAccessPolicy;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.server.security.SessionManager;

import java.time.Instant;
import java.util.Map;

public final class RequestRouter {
    private final AuthService authService;
    private final StudentService studentService;
    private final AcademicService academicService;
    private final TeacherProfileService teacherProfileService;
    private final AccountService accountService;
    private final NotificationService notificationService;
    private final LibraryService libraryService;
    private final ForumService forumService;
    private final BankService bankService;
    private final SessionManager sessions;

    public RequestRouter(
            AuthService authService,
            StudentService studentService,
            AcademicService academicService,
            TeacherProfileService teacherProfileService,
            AccountService accountService,
            NotificationService notificationService,
            LibraryService libraryService,
            ForumService forumService,
            BankService bankService,
            SessionManager sessions) {
        this.authService = authService;
        this.studentService = studentService;
        this.academicService = academicService;
        this.teacherProfileService = teacherProfileService;
        this.accountService = accountService;
        this.notificationService = notificationService;
        this.libraryService = libraryService;
        this.forumService = forumService;
        this.bankService = bankService;
        this.sessions = sessions;
    }

    public RequestRouter(
            AuthService authService,
            StudentService studentService,
            AcademicService academicService,
            TeacherProfileService teacherProfileService,
            AccountService accountService,
            NotificationService notificationService,
            LibraryService libraryService,
            ForumService forumService,
            SessionManager sessions) {
        this(authService, studentService, academicService, teacherProfileService,
                accountService, notificationService, libraryService, forumService, null, sessions);
    }

    public ResponseMessage route(RequestMessage request, String clientAddress) {
        String action = request.action();
        if (!Actions.SYSTEM_PING.equals(action)
                && !Actions.AUTH_LOGIN.equals(action)
                && sessions.requiresPasswordChange(request.parameters().get("sessionToken"))
                && !ForcedPasswordAccessPolicy.isAllowed(action)) {
            return ResponseMessage.failure(request.requestId(), "请先修改初始密码");
        }
        return switch (request.action()) {
            case Actions.SYSTEM_PING -> ResponseMessage.success(
                    request.requestId(),
                    "VCampus server is available",
                    Map.of("serverTime", Instant.now().toString(), "protocolVersion", "1"));
            case Actions.AUTH_LOGIN -> authService.login(request, clientAddress);
            case Actions.AUTH_LOGOUT -> authService.logout(request, clientAddress);
            case Actions.AUTH_SESSION -> authService.currentSession(request);
            case Actions.AUTH_CHANGE_PASSWORD -> authService.changePassword(request, clientAddress);
            case Actions.ACCOUNT_SEARCH -> accountService.search(request);
            case Actions.ACCOUNT_REFERENCE_DATA -> accountService.referenceData(request);
            case Actions.ACCOUNT_CREATE -> accountService.create(request);
            case Actions.ACCOUNT_UPDATE_ROLES -> accountService.updateRoles(request);
            case Actions.ACCOUNT_SET_ENABLED -> accountService.setEnabled(request);
            case Actions.ACCOUNT_RESET_PASSWORD -> accountService.resetPassword(request);
            case Actions.STUDENT_GET_SELF -> studentService.getSelf(request);
            case Actions.STUDENT_GET -> studentService.get(request);
            case Actions.STUDENT_SEARCH -> studentService.search(request);
            case Actions.STUDENT_UPDATE -> studentService.update(request);
            case Actions.STUDENT_UPDATE_CONTACT -> studentService.updateContact(request);
            case Actions.STUDENT_CHANGE_STATUS -> studentService.changeStatus(request);
            case Actions.STUDENT_STATUS_HISTORY -> studentService.statusHistory(request);
            case Actions.STUDENT_REFERENCE_DATA -> studentService.referenceData(request);
            case Actions.TEACHER_PROFILE_GET_SELF -> teacherProfileService.getSelf(request);
            case Actions.TEACHER_PROFILE_UPDATE_CONTACT -> teacherProfileService.updateContact(request);
            case Actions.ACADEMIC_REFERENCE_DATA -> academicService.referenceData(request);
            case Actions.ACADEMIC_COURSE_SEARCH -> academicService.searchCourses(request);
            case Actions.ACADEMIC_COURSE_CREATE -> academicService.createCourse(request);
            case Actions.ACADEMIC_COURSE_UPDATE -> academicService.updateCourse(request);
            case Actions.ACADEMIC_SECTION_SEARCH -> academicService.searchSections(request);
            case Actions.ACADEMIC_SECTION_CREATE -> academicService.createSection(request);
            case Actions.ACADEMIC_SECTION_SET_STATUS -> academicService.setSectionStatus(request);
            case Actions.ACADEMIC_ENROLLMENT_AVAILABLE -> academicService.availableSections(request);
            case Actions.ACADEMIC_ENROLLMENT_ENROLL -> academicService.enroll(request);
            case Actions.ACADEMIC_ENROLLMENT_DROP -> academicService.drop(request);
            case Actions.ACADEMIC_SCHEDULE_MY -> academicService.mySchedule(request);
            case Actions.ACADEMIC_SCHEDULE_TEACHER -> academicService.teacherSchedule(request);
            case Actions.ACADEMIC_TEACHER_SECTIONS -> academicService.teacherSections(request);
            case Actions.ACADEMIC_SECTION_ROSTER -> academicService.roster(request);
            case Actions.ACADEMIC_GRADE_SAVE -> academicService.saveGrade(request);
            case Actions.ACADEMIC_GRADE_PUBLISH -> academicService.publishGrades(request);
            case Actions.ACADEMIC_GRADE_MY -> academicService.myGrades(request);
            case Actions.NOTIFICATION_SEARCH -> notificationService.search(request);
            case Actions.NOTIFICATION_GET -> notificationService.get(request);
            case Actions.NOTIFICATION_UNREAD_COUNT -> notificationService.unreadCount(request);
            case Actions.NOTIFICATION_MARK_READ -> notificationService.markRead(request);
            case Actions.NOTIFICATION_MARK_ALL_READ -> notificationService.markAllRead(request);
            case Actions.LIBRARY_CATALOG_SEARCH -> libraryService.searchCatalog(request);
            case Actions.LIBRARY_CATALOG_GET -> libraryService.getCatalogItem(request);
            case Actions.LIBRARY_LOAN_MY -> libraryService.myLoans(request);
            case Actions.LIBRARY_LOAN_BORROW -> libraryService.borrow(request);
            case Actions.LIBRARY_LOAN_RETURN -> libraryService.returnLoan(request);
            case Actions.LIBRARY_LOAN_RENEW -> libraryService.renew(request);
            case Actions.LIBRARY_ADMIN_BOOK_CREATE -> libraryService.createBook(request);
            case Actions.LIBRARY_ADMIN_BOOK_UPDATE -> libraryService.updateBook(request);
            case Actions.LIBRARY_ADMIN_BOOK_SET_ENABLED -> libraryService.setBookEnabled(request);
            case Actions.LIBRARY_ADMIN_COPY_SEARCH -> libraryService.searchCopies(request);
            case Actions.LIBRARY_ADMIN_COPY_CREATE -> libraryService.createCopy(request);
            case Actions.LIBRARY_ADMIN_COPY_SET_STATUS -> libraryService.setCopyStatus(request);
            case Actions.LIBRARY_ADMIN_LOAN_SEARCH -> libraryService.searchLoans(request);
            case Actions.LIBRARY_ADMIN_CIRCULATION_PREVIEW -> libraryService.previewCirculation(request);
            case Actions.LIBRARY_ADMIN_LOAN_BORROW -> libraryService.adminBorrow(request);
            case Actions.LIBRARY_ADMIN_LOAN_RETURN -> libraryService.adminReturn(request);
            case Actions.FORUM_SECTION_LIST -> forumService.listSections(request);
            case Actions.FORUM_POST_SEARCH -> forumService.searchPosts(request);
            case Actions.FORUM_POST_GET -> forumService.getPost(request);
            case Actions.FORUM_POST_CREATE -> forumService.createPost(request);
            case Actions.FORUM_POST_DELETE -> forumService.deletePost(request);
            case Actions.FORUM_COMMENT_LIST -> forumService.listComments(request);
            case Actions.FORUM_COMMENT_CREATE -> forumService.createComment(request);
            case Actions.FORUM_COMMENT_DELETE -> forumService.deleteComment(request);
            case Actions.FORUM_ADMIN_SECTION_SAVE -> forumService.saveSection(request);
            case Actions.FORUM_ADMIN_SECTION_SET_ENABLED ->
                    forumService.setSectionEnabled(request);
            case Actions.FORUM_ADMIN_CONTENT_SEARCH -> forumService.searchAdminContent(request);
            case Actions.FORUM_ADMIN_POST_MODERATE -> forumService.moderatePost(request);
            case Actions.FORUM_ADMIN_COMMENT_MODERATE -> forumService.moderateComment(request);
            case Actions.FORUM_ADMIN_LOG_SEARCH -> forumService.searchModerationLogs(request);
            case Actions.BANK_ACCOUNT_GET -> bankService.account(request);
            case Actions.BANK_TRANSFER_CREATE -> bankService.transfer(request);
            case Actions.BANK_LEDGER_SEARCH -> bankService.searchLedger(request);
            case Actions.BANK_ADMIN_ACCOUNT_SEARCH -> bankService.searchAccounts(request);
            case Actions.BANK_ADMIN_TOPUP -> bankService.topUp(request);
            case Actions.BANK_ADMIN_FREEZE -> bankService.freeze(request);
            case Actions.BANK_ADMIN_UNFREEZE -> bankService.unfreeze(request);
            default -> ResponseMessage.failure(
                    request.requestId(),
                    "Unsupported action: " + request.action());
        };
    }
}
