package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.AccountAccessPolicy;
import com.vcampus.common.model.AcademicAccessPolicy;
import com.vcampus.common.model.LibraryAccessPolicy;
import com.vcampus.common.model.RoleCompositionPolicy;
import com.vcampus.common.model.UserRole;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dialog;
import java.awt.Font;
import java.awt.Window;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * EDT-confined host for the pre-JavaFX business panels in one active session.
 */
public final class LegacyModuleBridge {
    private final VCampusClient client;
    private final String token;
    private final Set<UserRole> roles;
    private final Runnable onWorkspace;
    private final Runnable onUnreadRefresh;
    private final Runnable onModule;
    private final java.util.function.LongConsumer externalForumNavigation;
    private final Runnable externalLibraryLoans;
    private final java.util.function.LongConsumer externalLibraryBook;
    private final java.util.function.LongConsumer externalShopOrder;
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final Map<String, JPanel> modules = new LinkedHashMap<>();
    private JPanel messagePanel;
    private JLabel messageLabel;
    private boolean closed;

    public LegacyModuleBridge(
            VCampusClient client,
            String token,
            Set<UserRole> roles,
            Runnable onWorkspace,
            Runnable onUnreadRefresh,
            Runnable onModule) {
        this(client, token, roles, onWorkspace, onUnreadRefresh, onModule, null);
    }

    public LegacyModuleBridge(VCampusClient client, String token, Set<UserRole> roles,
                              Runnable onWorkspace, Runnable onUnreadRefresh, Runnable onModule,
                              java.util.function.LongConsumer externalForumNavigation) {
        this(client, token, roles, onWorkspace, onUnreadRefresh, onModule, externalForumNavigation, null, null, null);
    }

    public LegacyModuleBridge(VCampusClient client, String token, Set<UserRole> roles,
                              Runnable onWorkspace, Runnable onUnreadRefresh, Runnable onModule,
                              java.util.function.LongConsumer externalForumNavigation,
                              Runnable externalLibraryLoans, java.util.function.LongConsumer externalLibraryBook) {
        this(client, token, roles, onWorkspace, onUnreadRefresh, onModule, externalForumNavigation,
                externalLibraryLoans, externalLibraryBook, null);
    }

    public LegacyModuleBridge(VCampusClient client, String token, Set<UserRole> roles,
                              Runnable onWorkspace, Runnable onUnreadRefresh, Runnable onModule,
                              java.util.function.LongConsumer externalForumNavigation,
                              Runnable externalLibraryLoans, java.util.function.LongConsumer externalLibraryBook,
                              java.util.function.LongConsumer externalShopOrder) {
        requireEdt();
        this.externalForumNavigation = externalForumNavigation;
        this.externalLibraryLoans = externalLibraryLoans;
        this.externalLibraryBook = externalLibraryBook;
        this.externalShopOrder = externalShopOrder;
        this.client = Objects.requireNonNull(client, "client");
        this.token = Objects.requireNonNull(token, "token");
        this.roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        RoleCompositionPolicy.requireValid(this.roles);
        this.onWorkspace = Objects.requireNonNull(onWorkspace, "onWorkspace");
        this.onUnreadRefresh = Objects.requireNonNull(onUnreadRefresh, "onUnreadRefresh");
        this.onModule = Objects.requireNonNull(onModule, "onModule");
        content.setBackground(Theme.BACKGROUND);
    }

    public JPanel content() {
        requireEdt();
        return content;
    }

    public void open(String route) {
        requireEdt();
        if (closed) {
            return;
        }
        openRoute(route, false);
    }

    public void close() {
        requireEdt();
        if (closed) {
            return;
        }
        closed = true;
        LegacyUiLifecycle.markClosed(content);
        disposeOwnedDialogs();
        modules.clear();
        content.removeAll();
        messagePanel = null;
        messageLabel = null;
        content.setVisible(false);
        content.revalidate();
        content.repaint();
    }

    private void openRoute(String route, boolean notificationNavigation) {
        if (route == null || route.isBlank()) {
            showMessage("未找到要打开的校园模块");
            return;
        }
        switch (route) {
            case "accounts" -> openAccounts(notificationNavigation);
            case "notifications" -> openNotifications(notificationNavigation);
            case "personal-profile" -> openPersonalProfile(notificationNavigation);
            case "student-status" -> openStudentStatus(notificationNavigation);
            case "academic" -> openAcademic(notificationNavigation);
            case "student-schedule" -> openStudentSchedule(notificationNavigation);
            case "teacher-schedule" -> openTeacherSchedule(notificationNavigation);
            case "library" -> openLibrary(notificationNavigation);
            case "library-loans" -> openLibraryLoans(notificationNavigation);
            case "shop" -> openShop(notificationNavigation);
            case "shop-orders" -> openShopOrders(notificationNavigation);
            case "bank" -> openBank(notificationNavigation);
            case "bank-ledger" -> openBankLedger(notificationNavigation);
            case "forum" -> openForum(notificationNavigation);
            default -> showMessage("未找到要打开的校园模块");
        }
    }

    private void openAccounts(boolean notificationNavigation) {
        if (!AccountAccessPolicy.canManageAccounts(roles)) {
            showUnauthorized();
            return;
        }
        AccountManagementPanel panel = module("accounts",
                () -> new AccountManagementPanel(client, token));
        show("accounts", panel);
        panel.activate();
        notifyModuleChange(notificationNavigation);
    }

    private void openNotifications(boolean notificationNavigation) {
        NotificationPanel panel = module("notifications", () -> new NotificationPanel(
                client, token, this::navigateFromNotification, onUnreadRefresh));
        show("notifications", panel);
        panel.activate();
        notifyModuleChange(notificationNavigation);
    }

    private void openPersonalProfile(boolean notificationNavigation) {
        if (roles.contains(UserRole.STUDENT)) {
            show("student-profile", module("student-profile",
                    () -> new StudentModulePanel(client, token, roles, this::returnWorkspace)));
        } else if (roles.contains(UserRole.TEACHER)) {
            show("teacher-profile", module("teacher-profile",
                    () -> new TeacherProfileModulePanel(client, token, this::returnWorkspace)));
        } else {
            showUnauthorized();
            return;
        }
        notifyModuleChange(notificationNavigation);
    }

    private void openStudentStatus(boolean notificationNavigation) {
        if (!(roles.contains(UserRole.SUPER_ADMIN) || roles.contains(UserRole.STUDENT_ADMIN))) {
            showUnauthorized();
            return;
        }
        show("student-status", module("student-status",
                () -> new StudentModulePanel(client, token, roles, this::returnWorkspace)));
        notifyModuleChange(notificationNavigation);
    }

    private void openAcademic(boolean notificationNavigation) {
        show("academic", academic());
        notifyModuleChange(notificationNavigation);
    }

    private void openStudentSchedule(boolean notificationNavigation) {
        if (!AcademicAccessPolicy.canStudy(roles)) {
            showUnauthorized();
            return;
        }
        AcademicModulePanel panel = academic();
        show("academic", panel);
        panel.openStudentSchedule();
        notifyModuleChange(notificationNavigation);
    }

    private void openTeacherSchedule(boolean notificationNavigation) {
        if (!AcademicAccessPolicy.canTeach(roles)) {
            showUnauthorized();
            return;
        }
        AcademicModulePanel panel = academic();
        show("academic", panel);
        panel.openTeacherSchedule();
        notifyModuleChange(notificationNavigation);
    }

    private void openLibrary(boolean notificationNavigation) {
        show("library", library());
        notifyModuleChange(notificationNavigation);
    }

    private void openLibraryLoans(boolean notificationNavigation) {
        if (!LibraryAccessPolicy.canBorrow(roles)) {
            showUnauthorized();
            return;
        }
        LibraryModulePanel panel = library();
        show("library", panel);
        panel.openMyLoans();
        notifyModuleChange(notificationNavigation);
    }

    private void openShop(boolean notificationNavigation) {
        ShopModulePanel panel = module("shop",
                () -> new ShopModulePanel(client, token, roles, this::returnWorkspace));
        show("shop", panel);
        panel.activate();
        notifyModuleChange(notificationNavigation);
    }

    private void openShopOrders(boolean notificationNavigation) {
        ShopModulePanel panel = module("shop",
                () -> new ShopModulePanel(client, token, roles, this::returnWorkspace));
        show("shop", panel);
        panel.activate();
        panel.openOrders();
        notifyModuleChange(notificationNavigation);
    }

    private void openBank(boolean notificationNavigation) {
        BankModulePanel panel = bank();
        show("bank", panel);
        panel.activate();
        notifyModuleChange(notificationNavigation);
    }

    private void openBankLedger(boolean notificationNavigation) {
        BankModulePanel panel = bank();
        show("bank", panel);
        panel.activate();
        panel.openLedger();
        notifyModuleChange(notificationNavigation);
    }

    private void openForum(boolean notificationNavigation) {
        ForumModulePanel panel = module("forum",
                () -> new ForumModulePanel(client, token, roles, this::returnWorkspace));
        show("forum", panel);
        panel.activate();
        notifyModuleChange(notificationNavigation);
    }

    private void navigateFromNotification(NotificationDestination destination) {
        requireEdt();
        if (closed) {
            return;
        }
        switch (destination.target()) {
            case TEACHER_SCHEDULE -> openRoute("teacher-schedule", true);
            case STUDENT_GRADES -> openStudentGrades();
            case STUDENT_PROFILE -> openStudentProfileFromNotification();
            case LIBRARY_LOANS -> {
                if(!LibraryAccessPolicy.canBorrow(roles)) {showUnauthorized();return;}
                if(externalLibraryLoans!=null) externalLibraryLoans.run(); else openRoute("library-loans", true);
            }
            case LIBRARY_CATALOG -> {
                Long bookId=destination.relatedEntityId();
                if(bookId==null||bookId<=0){showMessage("消息缺少有效的书目编号");return;}
                if(externalLibraryBook!=null) externalLibraryBook.accept(bookId);
                else {LibraryModulePanel panel=library();show("library",panel);panel.openBook(bookId);notifyModuleChange(true);}
            }
            case FORUM_POST -> openForumPost(destination);
            case BANK_LEDGER -> openRoute("bank-ledger", true);
            case SHOP_ORDERS -> openShopOrder(destination);
            case NONE -> {
                // Account-security notifications intentionally have no destination.
            }
        }
    }

    private void openStudentGrades() {
        if (!AcademicAccessPolicy.canStudy(roles)) {
            showUnauthorized();
            return;
        }
        AcademicModulePanel panel = academic();
        show("academic", panel);
        panel.openStudentGrades();
        notifyModuleChange(true);
    }

    private void openStudentProfileFromNotification() {
        if (!roles.contains(UserRole.STUDENT)) {
            showUnauthorized();
            return;
        }
        show("student-profile", module("student-profile",
                () -> new StudentModulePanel(client, token, roles, this::returnWorkspace)));
        notifyModuleChange(true);
    }

    private void openForumPost(NotificationDestination destination) {
        final long postId;
        try {
            postId = NotificationNavigationPolicy.forumPostId(destination);
        } catch (IllegalArgumentException exception) {
            showMessage("消息缺少有效的论坛帖子编号");
            return;
        }
        if (externalForumNavigation != null) {
            externalForumNavigation.accept(postId);
            return;
        }
        ForumModulePanel panel = module("forum",
                () -> new ForumModulePanel(client, token, roles, this::returnWorkspace));
        show("forum", panel);
        panel.activate();
        panel.openPost(postId);
        notifyModuleChange(true);
    }

    private void openShopOrder(NotificationDestination destination) {
        final long orderId;
        try {
            orderId = destination.shopOrderId();
        } catch (IllegalArgumentException error) {
            showMessage(error.getMessage());
            return;
        }
        if (externalShopOrder != null) {
            externalShopOrder.accept(orderId);
            return;
        }
        ShopModulePanel panel = module("shop",
                () -> new ShopModulePanel(client, token, roles, this::returnWorkspace));
        show("shop", panel);
        panel.activate();
        panel.openOrder(orderId);
        notifyModuleChange(true);
    }

    private AcademicModulePanel academic() {
        return module("academic",
                () -> new AcademicModulePanel(client, token, roles, this::returnWorkspace));
    }

    private LibraryModulePanel library() {
        return module("library",
                () -> new LibraryModulePanel(client, token, roles, this::returnWorkspace));
    }

    private BankModulePanel bank() {
        return module("bank",
                () -> new BankModulePanel(client, token, roles, this::returnWorkspace));
    }

    @SuppressWarnings("unchecked")
    private <T extends JPanel> T module(String route, PanelFactory<T> factory) {
        JPanel panel = modules.get(route);
        if (panel == null) {
            panel = factory.create();
            modules.put(route, panel);
            content.add(panel, route);
        }
        return (T) panel;
    }

    private void show(String route, JPanel panel) {
        cards.show(content, route);
        content.revalidate();
        content.repaint();
    }

    private void showUnauthorized() {
        showMessage("当前账号无权访问此模块");
    }

    private void showMessage(String message) {
        if (messagePanel == null) {
            messagePanel = new JPanel(new BorderLayout());
            messagePanel.setBackground(Theme.BACKGROUND);
            messagePanel.setBorder(BorderFactory.createEmptyBorder(36, 36, 36, 36));
            messageLabel = new JLabel("", SwingConstants.CENTER);
            messageLabel.setForeground(Theme.MUTED);
            messageLabel.setFont(messageLabel.getFont().deriveFont(Font.BOLD, 16f));
            messagePanel.add(messageLabel, BorderLayout.CENTER);
            content.add(messagePanel, "message");
        }
        messageLabel.setText(message);
        cards.show(content, "message");
        content.revalidate();
        content.repaint();
    }

    private void returnWorkspace() {
        if (!closed) {
            onWorkspace.run();
        }
    }

    private void disposeOwnedDialogs() {
        Window host = SwingUtilities.getWindowAncestor(content);
        if (host != null) {
            disposeOwnedDialogs(host);
        }
    }

    private void disposeOwnedDialogs(Window owner) {
        for (Window child : owner.getOwnedWindows()) {
            LegacyUiLifecycle.markClosed(child);
            disposeOwnedDialogs(child);
            if (child instanceof Dialog dialog) {
                dialog.dispose();
            }
        }
    }

    private void notifyModuleChange(boolean notificationNavigation) {
        if (notificationNavigation && !closed) {
            onModule.run();
        }
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("LegacyModuleBridge 必须在 Swing 事件派发线程上调用");
        }
    }

    @FunctionalInterface
    private interface PanelFactory<T extends JPanel> {
        T create();
    }
}
