package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.AccountAccessPolicy;
import com.vcampus.common.model.AcademicAccessPolicy;
import com.vcampus.common.model.LibraryAccessPolicy;
import com.vcampus.common.model.ModuleCode;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.UserRole;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class MainFrame extends JFrame {
    private final VCampusClient client;
    private final String sessionToken;
    private final Set<UserRole> roles;
    private final MainContentHost contentHost = new MainContentHost();
    private final AccountManagementPanel accountManagementPanel;
    private final NotificationPanel notificationPanel;
    private final JLabel notificationBadge = new JLabel("", SwingConstants.CENTER);
    private final UnreadNotificationPoller unreadPoller;
    private JButton selectedNavigation;
    private JButton workspaceNavigation;

    public MainFrame(
            VCampusClient client,
            String sessionToken,
            String displayName,
            Set<UserRole> roles) {
        super("VCampus 虚拟校园系统");
        this.client = client;
        this.sessionToken = sessionToken;
        this.roles = Set.copyOf(roles);
        this.accountManagementPanel = AccountAccessPolicy.canManageAccounts(this.roles)
                ? new AccountManagementPanel(client, sessionToken) : null;
        this.notificationPanel = new NotificationPanel(
                client, sessionToken, this::navigateFromNotification, this::refreshUnreadNow);
        this.unreadPoller = new UnreadNotificationPoller(
                this::requestUnreadCount,
                this::updateUnreadBadge,
                error -> System.err.println("Unread notification refresh failed: " + error.getMessage()),
                Duration.ofSeconds(10));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1000, 650));
        setSize(1180, 760);
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.BACKGROUND);
        contentHost.register("workspace", createWorkspace(displayName));
        if (accountManagementPanel != null) {
            contentHost.register("accounts", accountManagementPanel);
        }
        contentHost.register("notifications", notificationPanel);
        root.add(createSidebar(), BorderLayout.WEST);
        root.add(contentHost, BorderLayout.CENTER);
        contentHost.show("workspace");
        setContentPane(root);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                unreadPoller.close();
            }

            @Override
            public void windowClosed(WindowEvent event) {
                unreadPoller.close();
            }
        });
        unreadPoller.start();
    }

    private JPanel createSidebar() {
        JPanel sidebar = new JPanel();
        sidebar.setBackground(Theme.SURFACE);
        sidebar.setPreferredSize(new Dimension(210, 0));
        sidebar.setBorder(BorderFactory.createEmptyBorder(28, 20, 24, 20));
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));

        JLabel brand = new JLabel("  VC  VCampus");
        brand.setOpaque(true);
        brand.setBackground(Theme.ACCENT);
        brand.setForeground(Theme.PRIMARY_TEXT);
        brand.setFont(brand.getFont().deriveFont(Font.BOLD, 17f));
        brand.setMaximumSize(new Dimension(Integer.MAX_VALUE, 45));
        brand.setAlignmentX(LEFT_ALIGNMENT);

        JLabel section = new JLabel("校园门户");
        section.setForeground(Theme.MUTED);
        section.setFont(section.getFont().deriveFont(Font.BOLD, 12f));
        section.setAlignmentX(LEFT_ALIGNMENT);

        sidebar.add(brand);
        sidebar.add(Box.createVerticalStrut(38));
        sidebar.add(section);
        sidebar.add(Box.createVerticalStrut(12));
        workspaceNavigation = navButton("▦  工作台", true);
        selectedNavigation = workspaceNavigation;
        workspaceNavigation.addActionListener(event -> showWorkspace());
        sidebar.add(workspaceNavigation);
        if (accountManagementPanel != null) {
            sidebar.add(Box.createVerticalStrut(8));
            JButton accounts = navButton("♙  账号管理", false);
            accounts.addActionListener(event -> {
                showContent("accounts", accounts);
                accountManagementPanel.activate();
            });
            sidebar.add(accounts);
        }
        sidebar.add(Box.createVerticalStrut(8));
        JButton notifications = navButton("●  消息中心", false);
        notifications.addActionListener(event -> {
            showContent("notifications", notifications);
            notificationPanel.activate();
        });
        sidebar.add(notificationNavigation(notifications));
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(navButton("◎  我的校园", false));
        sidebar.add(Box.createVerticalGlue());

        JButton logout = navButton("↪  退出登录", false);
        logout.addActionListener(event -> logout(logout));
        sidebar.add(logout);
        return sidebar;
    }

    private JButton navButton(String text, boolean selected) {
        JButton button = new JButton(text);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setForeground(selected ? Theme.ACCENT : Theme.MUTED);
        button.setBackground(selected ? Theme.SECONDARY : Theme.SURFACE);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        button.setAlignmentX(LEFT_ALIGNMENT);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private JPanel notificationNavigation(JButton button) {
        JPanel entry = new JPanel(new BorderLayout(4, 0));
        entry.setOpaque(false);
        entry.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        entry.setAlignmentX(LEFT_ALIGNMENT);
        notificationBadge.setOpaque(true);
        notificationBadge.setBackground(Theme.DANGER);
        notificationBadge.setForeground(Theme.PRIMARY_TEXT);
        notificationBadge.setFont(notificationBadge.getFont().deriveFont(Font.BOLD, 11f));
        notificationBadge.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        notificationBadge.setVisible(false);
        entry.add(button, BorderLayout.CENTER);
        entry.add(notificationBadge, BorderLayout.EAST);
        return entry;
    }

    private void showContent(String name, JButton navigation) {
        contentHost.show(name);
        selectNavigation(navigation);
    }

    private void selectNavigation(JButton navigation) {
        if (selectedNavigation != null) {
            styleNavigation(selectedNavigation, false);
        }
        styleNavigation(navigation, true);
        selectedNavigation = navigation;
    }

    private void showWorkspace() {
        showContent("workspace", workspaceNavigation);
    }

    private LibraryModulePanel showLibrary() {
        LibraryModulePanel panel = contentHost.showLazy(
                "library",
                () -> new LibraryModulePanel(client, sessionToken, roles, this::showWorkspace));
        selectNavigation(workspaceNavigation);
        return panel;
    }

    private void showLibraryLoans() {
        LibraryModulePanel panel = showLibrary();
        panel.openMyLoans();
    }

    private void showPersonalProfile() {
        switch (ProfileModuleKind.forRoles(roles)) {
            case STUDENT -> showStudentProfile();
            case TEACHER -> showTeacherProfile();
        }
    }

    private StudentModulePanel showStudentProfile() {
        StudentModulePanel panel = contentHost.showLazy(
                "student-profile",
                () -> new StudentModulePanel(client, sessionToken, roles, this::showWorkspace));
        selectNavigation(workspaceNavigation);
        return panel;
    }

    private StudentModulePanel showStudentManagement() {
        StudentModulePanel panel = contentHost.showLazy(
                "student-management",
                () -> new StudentModulePanel(client, sessionToken, roles, this::showWorkspace));
        selectNavigation(workspaceNavigation);
        return panel;
    }

    private TeacherProfileModulePanel showTeacherProfile() {
        TeacherProfileModulePanel panel = contentHost.showLazy(
                "teacher-profile",
                () -> new TeacherProfileModulePanel(
                        client, sessionToken, this::showWorkspace));
        selectNavigation(workspaceNavigation);
        return panel;
    }

    private AcademicModulePanel showAcademic() {
        AcademicModulePanel panel = contentHost.showLazy(
                "academic",
                () -> new AcademicModulePanel(client, sessionToken, roles, this::showWorkspace));
        selectNavigation(workspaceNavigation);
        return panel;
    }

    private ForumModulePanel showForum() {
        ForumModulePanel panel = contentHost.showLazy(
                "forum",
                () -> new ForumModulePanel(client, sessionToken, roles, this::showWorkspace));
        selectNavigation(workspaceNavigation);
        panel.activate();
        return panel;
    }

    private BankModulePanel showBank() {
        BankModulePanel panel = contentHost.showLazy(
                "bank",
                () -> new BankModulePanel(client, sessionToken, roles, this::showWorkspace));
        selectNavigation(workspaceNavigation);
        panel.activate();
        return panel;
    }

    private void showTeacherSchedule() {
        AcademicModulePanel panel = showAcademic();
        panel.openTeacherSchedule();
    }

    private void showStudentGrades() {
        AcademicModulePanel panel = showAcademic();
        panel.openStudentGrades();
    }

    private void styleNavigation(JButton button, boolean selected) {
        button.setForeground(selected ? Theme.ACCENT : Theme.MUTED);
        button.setBackground(selected ? Theme.SECONDARY : Theme.SURFACE);
    }

    private JPanel createWorkspace(String displayName) {
        JPanel workspace = new JPanel(new BorderLayout(0, 22));
        workspace.setOpaque(false);
        workspace.setBorder(BorderFactory.createEmptyBorder(30, 36, 34, 36));
        workspace.add(createHeader(displayName), BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(0, 16));
        body.setOpaque(false);
        JLabel sectionTitle = new JLabel("常用应用");
        sectionTitle.setForeground(Theme.MUTED);
        sectionTitle.setFont(sectionTitle.getFont().deriveFont(Font.BOLD, 15f));
        body.add(sectionTitle, BorderLayout.NORTH);

        List<WorkspaceCardSpec> cards = WorkspaceCardResolver.resolve(roles);
        RoundedPanel cardContainer = new RoundedPanel(Theme.SURFACE, 20);
        cardContainer.setLayout(new GridLayout(0, 3, 16, 16));
        cardContainer.setBorder(BorderFactory.createEmptyBorder(22, 22, 22, 22));
        cards.forEach(card -> cardContainer.add(new ModuleCard(card)));
        cardContainer.setPreferredSize(new Dimension(850, cards.size() > 6 ? 650 : 430));

        JScrollPane cardScroll = new JScrollPane(
                cardContainer,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        cardScroll.setBorder(null);
        cardScroll.setOpaque(false);
        cardScroll.getViewport().setOpaque(false);
        cardScroll.getVerticalScrollBar().setUnitIncrement(18);
        body.add(cardScroll, BorderLayout.CENTER);

        JLabel footer = new JLabel("VCampus · 数据库 → 应用服务器 → Swing 客户端");
        footer.setForeground(Theme.MUTED.darker());
        footer.setBorder(BorderFactory.createEmptyBorder(5, 2, 0, 0));
        body.add(footer, BorderLayout.SOUTH);

        workspace.add(body, BorderLayout.CENTER);
        return workspace;
    }

    private JPanel createHeader(String displayName) {
        JPanel header = new JPanel(new BorderLayout(18, 0));
        header.setOpaque(false);

        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("工作台");
        title.setForeground(Theme.TEXT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 30f));
        JLabel welcome = new JLabel("欢迎回来，" + displayName);
        welcome.setForeground(Theme.MUTED);
        titles.add(title);
        titles.add(Box.createVerticalStrut(5));
        titles.add(welcome);

        JPanel tools = new JPanel(new BorderLayout(12, 0));
        tools.setOpaque(false);
        JTextField search = new JTextField("搜索校园服务");
        search.setForeground(Theme.MUTED);
        search.setPreferredSize(new Dimension(215, 40));
        Theme.styleField(search);
        JLabel avatar = new JLabel(initial(displayName), SwingConstants.CENTER);
        avatar.setOpaque(true);
        avatar.setBackground(Theme.SECONDARY);
        avatar.setForeground(Theme.ACCENT);
        avatar.setFont(avatar.getFont().deriveFont(Font.BOLD, 15f));
        avatar.setPreferredSize(new Dimension(42, 42));
        avatar.setToolTipText("当前角色：" + roleNames());
        tools.add(search, BorderLayout.CENTER);
        tools.add(avatar, BorderLayout.EAST);

        header.add(titles, BorderLayout.WEST);
        header.add(tools, BorderLayout.EAST);
        return header;
    }

    private String initial(String name) {
        return name == null || name.isBlank() ? "VC" : name.substring(0, 1).toUpperCase();
    }

    private String roleNames() {
        return roles.stream().map(UserRole::name).sorted().reduce((left, right) -> left + ", " + right)
                .orElse("未分配角色");
    }

    private void logout(JButton logoutButton) {
        unreadPoller.close();
        logoutButton.setEnabled(false);
        CompletableFuture.runAsync(() -> {
            try {
                client.logout(sessionToken);
            } catch (Exception exception) {
                System.err.println("Server logout failed: " + exception.getMessage());
            }
        }).whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
            dispose();
            new LoginFrame().setVisible(true);
        }));
    }

    private void navigateFromNotification(NotificationDestination destination) {
        switch (destination.target()) {
            case TEACHER_SCHEDULE -> {
                if (!AcademicAccessPolicy.canTeach(roles)) {
                    showUnavailableTarget();
                    return;
                }
                showTeacherSchedule();
            }
            case STUDENT_GRADES -> {
                if (!AcademicAccessPolicy.canStudy(roles)) {
                    showUnavailableTarget();
                    return;
                }
                showStudentGrades();
            }
            case STUDENT_PROFILE -> {
                if (!roles.contains(UserRole.STUDENT)) {
                    showUnavailableTarget();
                    return;
                }
                showStudentProfile();
            }
            case LIBRARY_LOANS -> {
                if (!LibraryAccessPolicy.canBorrow(roles)) {
                    showUnavailableTarget();
                    return;
                }
                showLibraryLoans();
            }
            case FORUM_POST -> {
                long postId = NotificationNavigationPolicy.forumPostId(destination);
                showForum().openPost(postId);
            }
            case BANK_LEDGER -> showBank().openLedger();
            case NONE -> {
                // Account-security notifications intentionally have no navigation target.
            }
        }
    }

    private void showUnavailableTarget() {
        JOptionPane.showMessageDialog(
                this, "该页面当前不可用", "消息详情", JOptionPane.INFORMATION_MESSAGE);
    }

    private CompletableFuture<Integer> requestUnreadCount() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return NotificationViewData.unreadCount(
                        client.unreadNotificationCount(sessionToken));
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
    }

    private void refreshUnreadNow() {
        unreadPoller.refreshNow();
    }

    private void updateUnreadBadge(int count) {
        Runnable update = () -> {
            String text = UnreadBadgeFormatter.format(count);
            notificationBadge.setText(text);
            notificationBadge.setVisible(!text.isEmpty());
            notificationBadge.getParent().revalidate();
            notificationBadge.getParent().repaint();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            update.run();
        } else {
            SwingUtilities.invokeLater(update);
        }
    }

    private final class ModuleCard extends RoundedPanel {
        private final ModuleCode module;

        private ModuleCard(WorkspaceCardSpec specification) {
            super(Theme.SURFACE_HOVER, 16);
            this.module = specification.module();
            setBorder(BorderFactory.createEmptyBorder(20, 20, 18, 20));
            setLayout(new BorderLayout(0, 14));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            JLabel icon = new JLabel(specification.iconText(), SwingConstants.CENTER);
            icon.setOpaque(true);
            icon.setBackground(Theme.SECONDARY);
            icon.setForeground(Theme.ACCENT);
            icon.setFont(icon.getFont().deriveFont(Font.BOLD, 18f));
            icon.setPreferredSize(new Dimension(52, 52));

            JPanel copy = new JPanel();
            copy.setOpaque(false);
            copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
            JLabel name = new JLabel(specification.title());
            name.setForeground(Theme.TEXT);
            name.setFont(name.getFont().deriveFont(Font.BOLD, 17f));
            JLabel description = new JLabel(specification.description());
            description.setForeground(Theme.MUTED);
            description.setFont(description.getFont().deriveFont(12f));
            copy.add(name);
            copy.add(Box.createVerticalStrut(7));
            copy.add(description);

            JLabel arrow = new JLabel("→");
            arrow.setForeground(Theme.ACCENT);
            arrow.setFont(arrow.getFont().deriveFont(Font.BOLD, 20f));

            add(icon, BorderLayout.NORTH);
            add(copy, BorderLayout.CENTER);
            add(arrow, BorderLayout.EAST);

            MouseAdapter openHandler = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    openModule();
                }
            };
            attachOpenHandler(this, openHandler);
        }

        private void attachOpenHandler(java.awt.Component component, MouseAdapter handler) {
            component.addMouseListener(handler);
            if (component instanceof java.awt.Container container) {
                for (java.awt.Component child : container.getComponents()) {
                    attachOpenHandler(child, handler);
                }
            }
        }

        private void openModule() {
            var embeddedRoute = MainModuleRoute.route(module);
            if (embeddedRoute.isPresent()) {
                switch (embeddedRoute.get()) {
                    case "library" -> showLibrary();
                    case "personal-profile" -> showPersonalProfile();
                    case "student-status" -> showStudentManagement();
                    case "academic" -> showAcademic();
                    case "forum" -> showForum();
                    case "bank" -> showBank();
                    default -> throw new IllegalStateException(
                            "未知的嵌入式模块路由: " + embeddedRoute.get());
                }
                return;
            }
            JOptionPane.showMessageDialog(MainFrame.this,
                    module.displayName() + "将在对应迭代中接入真实业务页面。",
                    module.displayName(),
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }
}
