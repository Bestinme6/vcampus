package com.vcampus.client.network;

import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.LibraryCirculationOperation;
import com.vcampus.common.model.LibraryReturnCondition;
import com.vcampus.common.model.ForumContentStatus;
import com.vcampus.common.model.ForumModerationAction;
import com.vcampus.common.model.ForumSort;
import com.vcampus.common.model.ForumTargetType;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.MessageCodec;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.stream.Collectors;
import com.vcampus.common.model.UserRole;

public final class VCampusClient {
    private static final int CONNECT_TIMEOUT_MILLIS = 3_000;
    private static final int READ_TIMEOUT_MILLIS = 5_000;

    private final String host;
    private final int port;

    public VCampusClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public ResponseMessage ping() throws IOException {
        return send(RequestMessage.create(Actions.SYSTEM_PING, Map.of()));
    }

    public ResponseMessage login(String username, char[] password) throws IOException {
        return send(RequestMessage.create(Actions.AUTH_LOGIN, Map.of(
                "username", username,
                "password", new String(password))));
    }

    public ResponseMessage logout(String sessionToken) throws IOException {
        return send(RequestMessage.create(Actions.AUTH_LOGOUT, Map.of(
                "sessionToken", sessionToken)));
    }

    public ResponseMessage currentSession(String sessionToken) throws IOException {
        return send(RequestMessage.create(Actions.AUTH_SESSION, Map.of(
                "sessionToken", sessionToken)));
    }

    public ResponseMessage changePassword(
            String sessionToken, char[] currentPassword, char[] newPassword) throws IOException {
        return sendAuthorized(Actions.AUTH_CHANGE_PASSWORD, sessionToken, Map.of(
                "currentPassword", new String(currentPassword),
                "newPassword", new String(newPassword)));
    }

    public ResponseMessage searchAccounts(
            String sessionToken, String keyword, String identity, String enabled, int page)
            throws IOException {
        return sendAuthorized(Actions.ACCOUNT_SEARCH, sessionToken, Map.of(
                "keyword", keyword,
                "identity", identity,
                "enabled", enabled,
                "page", Integer.toString(page)));
    }

    public ResponseMessage accountReferenceData(String sessionToken) throws IOException {
        return sendAuthorized(Actions.ACCOUNT_REFERENCE_DATA, sessionToken, Map.of());
    }

    public ResponseMessage createAccount(String sessionToken, Map<String, String> values)
            throws IOException {
        return sendAuthorized(Actions.ACCOUNT_CREATE, sessionToken, values);
    }

    public ResponseMessage updateAccountRoles(
            String sessionToken, long userId, Set<UserRole> roles) throws IOException {
        return sendAuthorized(Actions.ACCOUNT_UPDATE_ROLES, sessionToken, Map.of(
                "userId", Long.toString(userId),
                "roles", roleNames(roles)));
    }

    public ResponseMessage setAccountEnabled(
            String sessionToken, long userId, boolean enabled) throws IOException {
        return sendAuthorized(Actions.ACCOUNT_SET_ENABLED, sessionToken, Map.of(
                "userId", Long.toString(userId),
                "enabled", Boolean.toString(enabled)));
    }

    public ResponseMessage resetAccountPassword(
            String sessionToken, long userId, char[] temporaryPassword) throws IOException {
        return sendAuthorized(Actions.ACCOUNT_RESET_PASSWORD, sessionToken, Map.of(
                "userId", Long.toString(userId),
                "temporaryPassword", new String(temporaryPassword)));
    }

    public ResponseMessage getMyStudentProfile(String sessionToken) throws IOException {
        return sendAuthorized(Actions.STUDENT_GET_SELF, sessionToken, Map.of());
    }

    public ResponseMessage searchStudents(
            String sessionToken, String keyword, String status, int page) throws IOException {
        return sendAuthorized(Actions.STUDENT_SEARCH, sessionToken, Map.of(
                "keyword", keyword,
                "status", status,
                "page", Integer.toString(page)));
    }

    public ResponseMessage getStudent(String sessionToken, long studentId) throws IOException {
        return sendAuthorized(Actions.STUDENT_GET, sessionToken, Map.of(
                "studentId", Long.toString(studentId)));
    }

    public ResponseMessage updateStudent(String sessionToken, Map<String, String> values) throws IOException {
        return sendAuthorized(Actions.STUDENT_UPDATE, sessionToken, values);
    }

    public ResponseMessage updateStudentContact(
            String sessionToken, Map<String, String> values) throws IOException {
        return sendAuthorized(Actions.STUDENT_UPDATE_CONTACT, sessionToken, values);
    }

    public ResponseMessage changeStudentStatus(
            String sessionToken, long studentId, String newStatus, String reason) throws IOException {
        return sendAuthorized(Actions.STUDENT_CHANGE_STATUS, sessionToken, Map.of(
                "studentId", Long.toString(studentId),
                "newStatus", newStatus,
                "reason", reason));
    }

    public ResponseMessage studentStatusHistory(String sessionToken, Long studentId) throws IOException {
        Map<String, String> values = studentId == null
                ? Map.of()
                : Map.of("studentId", Long.toString(studentId));
        return sendAuthorized(Actions.STUDENT_STATUS_HISTORY, sessionToken, values);
    }

    public ResponseMessage studentReferenceData(String sessionToken) throws IOException {
        return sendAuthorized(Actions.STUDENT_REFERENCE_DATA, sessionToken, Map.of());
    }

    public ResponseMessage getMyTeacherProfile(String sessionToken) throws IOException {
        return sendAuthorized(Actions.TEACHER_PROFILE_GET_SELF, sessionToken, Map.of());
    }

    public ResponseMessage updateTeacherContact(
            String sessionToken, String phone, String email) throws IOException {
        return sendAuthorized(Actions.TEACHER_PROFILE_UPDATE_CONTACT, sessionToken, Map.of(
                "phone", phone,
                "email", email));
    }

    public ResponseMessage academicReferenceData(String sessionToken) throws IOException {
        return sendAuthorized(Actions.ACADEMIC_REFERENCE_DATA, sessionToken, Map.of());
    }

    public ResponseMessage searchCourses(
            String sessionToken, String keyword, int page) throws IOException {
        return sendAuthorized(Actions.ACADEMIC_COURSE_SEARCH, sessionToken, Map.of(
                "keyword", keyword, "page", Integer.toString(page)));
    }

    public ResponseMessage createCourse(String sessionToken, Map<String, String> values) throws IOException {
        return sendAuthorized(Actions.ACADEMIC_COURSE_CREATE, sessionToken, values);
    }

    public ResponseMessage updateCourse(String sessionToken, Map<String, String> values) throws IOException {
        return sendAuthorized(Actions.ACADEMIC_COURSE_UPDATE, sessionToken, values);
    }

    public ResponseMessage searchCourseSections(
            String sessionToken, long termId, String keyword, int page) throws IOException {
        return sendAuthorized(Actions.ACADEMIC_SECTION_SEARCH, sessionToken, Map.of(
                "termId", Long.toString(termId),
                "keyword", keyword,
                "page", Integer.toString(page)));
    }

    public ResponseMessage createCourseSection(
            String sessionToken, Map<String, String> values) throws IOException {
        return sendAuthorized(Actions.ACADEMIC_SECTION_CREATE, sessionToken, values);
    }

    public ResponseMessage setCourseSectionStatus(
            String sessionToken, long sectionId, String status) throws IOException {
        return sendAuthorized(Actions.ACADEMIC_SECTION_SET_STATUS, sessionToken, Map.of(
                "sectionId", Long.toString(sectionId), "status", status));
    }

    public ResponseMessage availableCourseSections(String sessionToken, long termId) throws IOException {
        return sendAuthorized(Actions.ACADEMIC_ENROLLMENT_AVAILABLE, sessionToken, Map.of(
                "termId", Long.toString(termId)));
    }

    public ResponseMessage enrollCourse(String sessionToken, long sectionId) throws IOException {
        return sendAuthorized(Actions.ACADEMIC_ENROLLMENT_ENROLL, sessionToken, Map.of(
                "sectionId", Long.toString(sectionId)));
    }

    public ResponseMessage dropCourse(String sessionToken, long sectionId) throws IOException {
        return sendAuthorized(Actions.ACADEMIC_ENROLLMENT_DROP, sessionToken, Map.of(
                "sectionId", Long.toString(sectionId)));
    }

    public ResponseMessage mySchedule(String sessionToken, long termId) throws IOException {
        return sendAuthorized(Actions.ACADEMIC_SCHEDULE_MY, sessionToken, Map.of(
                "termId", Long.toString(termId)));
    }

    public ResponseMessage teacherSchedule(
            String sessionToken, long termId, Long teacherUserId) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("termId", Long.toString(termId));
        if (teacherUserId != null) {
            values.put("teacherUserId", Long.toString(teacherUserId));
        }
        return sendAuthorized(Actions.ACADEMIC_SCHEDULE_TEACHER, sessionToken, values);
    }

    public ResponseMessage teacherSections(String sessionToken, long termId) throws IOException {
        return sendAuthorized(Actions.ACADEMIC_TEACHER_SECTIONS, sessionToken, Map.of(
                "termId", Long.toString(termId)));
    }

    public ResponseMessage sectionRoster(String sessionToken, long sectionId) throws IOException {
        return sendAuthorized(Actions.ACADEMIC_SECTION_ROSTER, sessionToken, Map.of(
                "sectionId", Long.toString(sectionId)));
    }

    public ResponseMessage saveGrade(String sessionToken, Map<String, String> values) throws IOException {
        return sendAuthorized(Actions.ACADEMIC_GRADE_SAVE, sessionToken, values);
    }

    public ResponseMessage publishGrades(String sessionToken, long sectionId) throws IOException {
        return sendAuthorized(Actions.ACADEMIC_GRADE_PUBLISH, sessionToken, Map.of(
                "sectionId", Long.toString(sectionId)));
    }

    public ResponseMessage myGrades(String sessionToken) throws IOException {
        return sendAuthorized(Actions.ACADEMIC_GRADE_MY, sessionToken, Map.of());
    }

    public ResponseMessage searchNotifications(
            String sessionToken, String keyword, NotificationSource source,
            Boolean read, int page) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("keyword", keyword == null ? "" : keyword);
        values.put("page", Integer.toString(page));
        if (source != null) {
            values.put("source", source.name());
        }
        if (read != null) {
            values.put("read", Boolean.toString(read));
        }
        return sendAuthorized(Actions.NOTIFICATION_SEARCH, sessionToken, values);
    }

    public ResponseMessage getNotification(String sessionToken, long notificationId)
            throws IOException {
        return sendAuthorized(Actions.NOTIFICATION_GET, sessionToken, Map.of(
                "notificationId", Long.toString(notificationId)));
    }

    public ResponseMessage unreadNotificationCount(String sessionToken) throws IOException {
        return sendAuthorized(Actions.NOTIFICATION_UNREAD_COUNT, sessionToken, Map.of());
    }

    public ResponseMessage markNotificationRead(String sessionToken, long notificationId)
            throws IOException {
        return sendAuthorized(Actions.NOTIFICATION_MARK_READ, sessionToken, Map.of(
                "notificationId", Long.toString(notificationId)));
    }

    public ResponseMessage markAllNotificationsRead(String sessionToken) throws IOException {
        return sendAuthorized(Actions.NOTIFICATION_MARK_ALL_READ, sessionToken, Map.of());
    }

    public ResponseMessage searchLibraryCatalog(String token,String keyword,String category,int page)throws IOException{return sendAuthorized(Actions.LIBRARY_CATALOG_SEARCH,token,Map.of("keyword",keyword,"category",category,"page",Integer.toString(page)));}
    public ResponseMessage searchLibraryCatalog(String token,String keyword,String category,int page,boolean includeDisabled,boolean newestFirst)throws IOException{return sendAuthorized(Actions.LIBRARY_CATALOG_SEARCH,token,Map.of("keyword",keyword,"category",category,"page",Integer.toString(page),"includeDisabled",Boolean.toString(includeDisabled),"newestFirst",Boolean.toString(newestFirst)));}
    public ResponseMessage getLibraryCatalogItem(String token,long bookId)throws IOException{return sendAuthorized(Actions.LIBRARY_CATALOG_GET,token,Map.of("bookId",Long.toString(bookId)));}
    public ResponseMessage myLibraryLoans(String token,String scope,int page)throws IOException{Map<String,String>v=new LinkedHashMap<>();v.put("page",Integer.toString(page));if("active".equals(scope))v.put("active","true");else if("history".equals(scope))v.put("active","false");else if("overdue".equals(scope))v.put("overdue","true");return sendAuthorized(Actions.LIBRARY_LOAN_MY,token,v);}
    public ResponseMessage borrowLibraryBook(String token,long bookId)throws IOException{return sendAuthorized(Actions.LIBRARY_LOAN_BORROW,token,Map.of("bookId",Long.toString(bookId)));}
    public ResponseMessage returnLibraryLoan(String token,long loanId)throws IOException{return sendAuthorized(Actions.LIBRARY_LOAN_RETURN,token,Map.of("loanId",Long.toString(loanId)));}
    public ResponseMessage renewLibraryLoan(String token,long loanId)throws IOException{return sendAuthorized(Actions.LIBRARY_LOAN_RENEW,token,Map.of("loanId",Long.toString(loanId)));}
    public ResponseMessage createLibraryBook(String token,Map<String,String>values)throws IOException{return sendAuthorized(Actions.LIBRARY_ADMIN_BOOK_CREATE,token,values);}
    public ResponseMessage updateLibraryBook(String token,Map<String,String>values)throws IOException{return sendAuthorized(Actions.LIBRARY_ADMIN_BOOK_UPDATE,token,values);}
    public ResponseMessage setLibraryBookEnabled(String token,long bookId,boolean enabled)throws IOException{return sendAuthorized(Actions.LIBRARY_ADMIN_BOOK_SET_ENABLED,token,Map.of("bookId",Long.toString(bookId),"enabled",Boolean.toString(enabled)));}
    public ResponseMessage searchLibraryCopies(String token,Map<String,String>filters)throws IOException{return sendAuthorized(Actions.LIBRARY_ADMIN_COPY_SEARCH,token,filters);}
    public ResponseMessage createLibraryCopy(String token,Map<String,String>values)throws IOException{return sendAuthorized(Actions.LIBRARY_ADMIN_COPY_CREATE,token,values);}
    public ResponseMessage setLibraryCopyStatus(String token,long copyId,String status,String reason)throws IOException{return sendAuthorized(Actions.LIBRARY_ADMIN_COPY_SET_STATUS,token,Map.of("copyId",Long.toString(copyId),"status",status,"reason",reason));}
    public ResponseMessage searchLibraryLoans(String token,Map<String,String>filters)throws IOException{return sendAuthorized(Actions.LIBRARY_ADMIN_LOAN_SEARCH,token,filters);}
    public ResponseMessage previewLibraryCirculation(String token,String username,String barcode,LibraryCirculationOperation operation)throws IOException{return sendAuthorized(Actions.LIBRARY_ADMIN_CIRCULATION_PREVIEW,token,Map.of("username",username,"barcode",barcode,"operation",operation.name()));}
    public ResponseMessage adminBorrowLibraryCopy(String token,String username,String barcode)throws IOException{return sendAuthorized(Actions.LIBRARY_ADMIN_LOAN_BORROW,token,Map.of("username",username,"barcode",barcode));}
    public ResponseMessage adminReturnLibraryCopy(String token,String barcode,LibraryReturnCondition condition,String reason)throws IOException{return sendAuthorized(Actions.LIBRARY_ADMIN_LOAN_RETURN,token,Map.of("barcode",barcode,"condition",condition.name(),"reason",reason));}

    public ResponseMessage listForumSections(String token, boolean includeDisabled)
            throws IOException {
        return sendAuthorized(Actions.FORUM_SECTION_LIST, token,
                Map.of("includeDisabled", Boolean.toString(includeDisabled)));
    }

    public ResponseMessage searchForumPosts(String token, Long sectionId, String keyword,
                                            ForumSort sort, int page) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        if (sectionId != null) values.put("sectionId", Long.toString(sectionId));
        values.put("keyword", keyword == null ? "" : keyword);
        values.put("sort", sort.name());
        values.put("page", Integer.toString(page));
        return sendAuthorized(Actions.FORUM_POST_SEARCH, token, values);
    }

    public ResponseMessage getForumPost(String token, long postId) throws IOException {
        return sendAuthorized(Actions.FORUM_POST_GET, token,
                Map.of("postId", Long.toString(postId)));
    }

    public ResponseMessage createForumPost(String token, long sectionId,
                                           String title, String content) throws IOException {
        return sendAuthorized(Actions.FORUM_POST_CREATE, token, Map.of(
                "sectionId", Long.toString(sectionId), "title", title, "content", content));
    }

    public ResponseMessage deleteForumPost(String token, long postId) throws IOException {
        return sendAuthorized(Actions.FORUM_POST_DELETE, token,
                Map.of("postId", Long.toString(postId)));
    }

    public ResponseMessage listForumComments(String token, long postId, int page)
            throws IOException {
        return sendAuthorized(Actions.FORUM_COMMENT_LIST, token, Map.of(
                "postId", Long.toString(postId), "page", Integer.toString(page)));
    }

    public ResponseMessage createForumComment(String token, long postId, String content)
            throws IOException {
        return sendAuthorized(Actions.FORUM_COMMENT_CREATE, token, Map.of(
                "postId", Long.toString(postId), "content", content));
    }

    public ResponseMessage deleteForumComment(String token, long commentId)
            throws IOException {
        return sendAuthorized(Actions.FORUM_COMMENT_DELETE, token,
                Map.of("commentId", Long.toString(commentId)));
    }

    public ResponseMessage saveForumSection(String token, Map<String, String> values)
            throws IOException {
        return sendAuthorized(Actions.FORUM_ADMIN_SECTION_SAVE, token, values);
    }

    public ResponseMessage setForumSectionEnabled(
            String token, long sectionId, boolean enabled) throws IOException {
        return sendAuthorized(Actions.FORUM_ADMIN_SECTION_SET_ENABLED, token, Map.of(
                "sectionId", Long.toString(sectionId), "enabled", Boolean.toString(enabled)));
    }

    public ResponseMessage searchForumAdminContent(
            String token, ForumTargetType targetType, ForumContentStatus status,
            String keyword, int page) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("targetType", targetType.name());
        if (status != null) values.put("status", status.name());
        values.put("keyword", keyword == null ? "" : keyword);
        values.put("page", Integer.toString(page));
        return sendAuthorized(Actions.FORUM_ADMIN_CONTENT_SEARCH, token, values);
    }

    public ResponseMessage moderateForumPost(
            String token, long postId, ForumModerationAction action, String reason)
            throws IOException {
        return sendAuthorized(Actions.FORUM_ADMIN_POST_MODERATE, token, Map.of(
                "postId", Long.toString(postId), "action", action.name(),
                "reason", reason == null ? "" : reason));
    }

    public ResponseMessage moderateForumComment(
            String token, long commentId, ForumModerationAction action, String reason)
            throws IOException {
        return sendAuthorized(Actions.FORUM_ADMIN_COMMENT_MODERATE, token, Map.of(
                "commentId", Long.toString(commentId), "action", action.name(),
                "reason", reason == null ? "" : reason));
    }

    public ResponseMessage searchForumModerationLogs(String token, int page)
            throws IOException {
        return sendAuthorized(Actions.FORUM_ADMIN_LOG_SEARCH, token,
                Map.of("page", Integer.toString(page)));
    }

    public ResponseMessage getBankAccount(String token) throws IOException {
        return sendAuthorized(Actions.BANK_ACCOUNT_GET, token, Map.of());
    }

    public ResponseMessage transferBank(
            String token, String recipientUsername, String amount, String operationId)
            throws IOException {
        return sendAuthorized(Actions.BANK_TRANSFER_CREATE, token, Map.of(
                "recipientUsername", recipientUsername,
                "amount", amount,
                "operationId", operationId));
    }

    public ResponseMessage searchBankLedger(
            String token, String targetUsername, String type, int page) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        if (targetUsername != null && !targetUsername.isBlank()) {
            values.put("targetUsername", targetUsername);
        }
        if (type != null && !type.isBlank()) values.put("type", type);
        values.put("page", Integer.toString(page));
        return sendAuthorized(Actions.BANK_LEDGER_SEARCH, token, values);
    }

    public ResponseMessage searchBankAccounts(
            String token, String keyword, String status, int page) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("keyword", keyword == null ? "" : keyword);
        if (status != null && !status.isBlank()) values.put("status", status);
        values.put("page", Integer.toString(page));
        return sendAuthorized(Actions.BANK_ADMIN_ACCOUNT_SEARCH, token, values);
    }

    public ResponseMessage topUpBankAccount(
            String token, String targetUsername, String amount, String operationId) throws IOException {
        return sendAuthorized(Actions.BANK_ADMIN_TOPUP, token, Map.of(
                "targetUsername", targetUsername,
                "amount", amount,
                "operationId", operationId));
    }

    public ResponseMessage setBankAccountFrozen(
            String token, String targetUsername, boolean frozen) throws IOException {
        return sendAuthorized(frozen ? Actions.BANK_ADMIN_FREEZE : Actions.BANK_ADMIN_UNFREEZE,
                token, Map.of("targetUsername", targetUsername));
    }

    public ResponseMessage searchShopProducts(
            String token, String keyword, Boolean enabled, int page) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("keyword", keyword == null ? "" : keyword);
        values.put("page", Integer.toString(page));
        if (enabled != null) values.put("enabled", Boolean.toString(enabled));
        return sendAuthorized(Actions.SHOP_PRODUCT_SEARCH, token, values);
    }

    public ResponseMessage getShopCart(String token) throws IOException {
        return sendAuthorized(Actions.SHOP_CART_GET, token, Map.of());
    }

    public ResponseMessage setShopCartQuantity(
            String token, long productId, int quantity) throws IOException {
        return sendAuthorized(Actions.SHOP_CART_SET_QUANTITY, token, Map.of(
                "productId", Long.toString(productId),
                "quantity", Integer.toString(quantity)));
    }

    public ResponseMessage removeShopCartItem(String token, long productId) throws IOException {
        return sendAuthorized(Actions.SHOP_CART_REMOVE, token,
                Map.of("productId", Long.toString(productId)));
    }

    public ResponseMessage checkoutShop(String token, String operationId) throws IOException {
        return sendAuthorized(Actions.SHOP_CHECKOUT, token,
                Map.of("operationId", operationId));
    }

    public ResponseMessage searchShopOrders(
            String token, String status, int page) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        if (status != null && !status.isBlank()) values.put("status", status);
        values.put("page", Integer.toString(page));
        return sendAuthorized(Actions.SHOP_ORDER_SEARCH, token, values);
    }

    public ResponseMessage getShopOrder(String token, long orderId) throws IOException {
        return sendAuthorized(Actions.SHOP_ORDER_GET, token,
                Map.of("orderId", Long.toString(orderId)));
    }

    public ResponseMessage cancelShopOrder(String token, long orderId) throws IOException {
        return sendAuthorized(Actions.SHOP_ORDER_CANCEL, token,
                Map.of("orderId", Long.toString(orderId)));
    }

    public ResponseMessage confirmShopOrder(String token, long orderId) throws IOException {
        return sendAuthorized(Actions.SHOP_ORDER_CONFIRM, token,
                Map.of("orderId", Long.toString(orderId)));
    }

    public ResponseMessage saveShopProduct(
            String token, Long productId, String name, String description,
            String price, boolean enabled) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        if (productId != null) values.put("productId", Long.toString(productId));
        values.put("name", name);
        values.put("description", description == null ? "" : description);
        values.put("price", price);
        values.put("enabled", Boolean.toString(enabled));
        return sendAuthorized(Actions.SHOP_ADMIN_PRODUCT_SAVE, token, values);
    }

    public ResponseMessage setShopProductEnabled(
            String token, long productId, boolean enabled) throws IOException {
        return sendAuthorized(Actions.SHOP_ADMIN_PRODUCT_SET_ENABLED, token, Map.of(
                "productId", Long.toString(productId),
                "enabled", Boolean.toString(enabled)));
    }

    public ResponseMessage adjustShopInventory(
            String token, long productId, int delta, String reason) throws IOException {
        return sendAuthorized(Actions.SHOP_ADMIN_INVENTORY_ADJUST, token, Map.of(
                "productId", Long.toString(productId),
                "delta", Integer.toString(delta),
                "reason", reason));
    }

    public ResponseMessage searchShopAdminOrders(
            String token, String keyword, String status, int page) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("keyword", keyword == null ? "" : keyword);
        if (status != null && !status.isBlank()) values.put("status", status);
        values.put("page", Integer.toString(page));
        return sendAuthorized(Actions.SHOP_ADMIN_ORDER_SEARCH, token, values);
    }

    public ResponseMessage shipShopOrder(String token, long orderId) throws IOException {
        return sendAuthorized(Actions.SHOP_ADMIN_ORDER_SHIP, token,
                Map.of("orderId", Long.toString(orderId)));
    }

    private ResponseMessage sendAuthorized(
            String action, String sessionToken, Map<String, String> parameters) throws IOException {
        Map<String, String> authorized = new LinkedHashMap<>(parameters);
        authorized.put("sessionToken", sessionToken);
        return send(RequestMessage.create(action, authorized));
    }

    private String roleNames(Set<UserRole> roles) {
        return roles.stream().map(UserRole::name).sorted().collect(Collectors.joining(","));
    }

    public ResponseMessage send(RequestMessage request) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            socket.setTcpNoDelay(true);
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                 DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
                MessageCodec.writeRequest(output, request);
                return MessageCodec.readResponse(input);
            }
        }
    }
}
