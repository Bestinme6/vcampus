package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.UserRole;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Font;
import java.util.Set;

final class ForumModulePanel extends JPanel {
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final ForumNavigation navigation = new ForumNavigation();
    private final ForumHomePanel home;
    private final ForumPostDetailPanel detail;
    private final ForumAdminPanel admin;

    ForumModulePanel(VCampusClient client, String sessionToken, Set<UserRole> roles,
                     Runnable backToWorkspace) {
        setLayout(new BorderLayout(0, 18));
        setBackground(Theme.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(24, 28, 28, 28));
        admin = ForumAdminTabPolicy.visible(roles)
                ? new ForumAdminPanel(client, sessionToken) : null;
        add(heading(backToWorkspace), BorderLayout.NORTH);

        home = new ForumHomePanel(client, sessionToken, this::openPost,
                navigation::rememberHome);
        detail = new ForumPostDetailPanel(client, sessionToken, this::openHome);
        body.setOpaque(false);
        body.add(home, "home");
        body.add(detail, "detail");
        if (admin != null) body.add(admin, "admin");
        add(body, BorderLayout.CENTER);
        openHome();
    }

    void activate() {
        home.activate(navigation.homeQuery());
    }

    void openPost(long postId) {
        navigation.openPost(postId);
        detail.open(postId);
        cards.show(body, "detail");
    }

    void openHome() {
        ForumNavigation.HomeQuery query = navigation.backHome();
        cards.show(body, "home");
        home.activate(query);
    }

    void openAdmin() {
        if (admin == null) return;
        cards.show(body, "admin");
        admin.activate();
    }

    JPanel body() {
        return body;
    }

    private JPanel heading(Runnable backToWorkspace) {
        JPanel heading = new JPanel(new BorderLayout(16, 0));
        heading.setOpaque(false);
        JPanel titles = new JPanel(new BorderLayout());
        titles.setOpaque(false);
        JLabel title = new JLabel("校园论坛");
        title.setForeground(Theme.TEXT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));
        JLabel subtitle = new JLabel("校园话题、经验分享与互助交流");
        subtitle.setForeground(Theme.MUTED);
        titles.add(title, BorderLayout.NORTH);
        titles.add(subtitle, BorderLayout.SOUTH);
        heading.add(titles, BorderLayout.WEST);
        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        JButton forumHome = new JButton("论坛首页");
        Theme.styleQuietButton(forumHome);
        forumHome.addActionListener(event -> openHome());
        actions.add(forumHome);
        if (admin != null) {
            JButton management = new JButton("内容管理");
            Theme.styleCommandButton(management);
            management.addActionListener(event -> openAdmin());
            actions.add(management);
        }
        if (backToWorkspace != null) {
            JButton back = new JButton("← 返回工作台");
            Theme.styleQuietButton(back);
            back.addActionListener(event -> backToWorkspace.run());
            actions.add(back);
        }
        heading.add(actions, BorderLayout.EAST);
        return heading;
    }
}
