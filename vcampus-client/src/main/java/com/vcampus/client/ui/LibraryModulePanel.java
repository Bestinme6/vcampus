package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.LibraryAccessPolicy;
import com.vcampus.common.model.UserRole;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.awt.BorderLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class LibraryModulePanel extends JPanel {
    private final JTabbedPane tabs = new JTabbedPane();

    LibraryModulePanel(
            VCampusClient client,
            String sessionToken,
            Set<UserRole> roles,
            Runnable backToWorkspace) {
        setLayout(new BorderLayout(0, 18));
        setBackground(Theme.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(24, 28, 28, 28));
        add(createHeading(backToWorkspace), BorderLayout.NORTH);

        tabs.setFont(tabs.getFont().deriveFont(Font.BOLD, 14f));
        if (LibraryAccessPolicy.canBorrow(roles)) {
            tabs.addTab("图书检索", new LibraryCatalogPanel(client, sessionToken));
            tabs.addTab("我的借阅", new MyLibraryLoansPanel(client, sessionToken));
        }
        if (LibraryAccessPolicy.canManage(roles)) {
            tabs.addTab("书目馆藏", new LibraryInventoryPanel(client, sessionToken));
            tabs.addTab("借还办理", new LibraryCirculationPanel(client, sessionToken));
            tabs.addTab("借阅查询", new LibraryLoanManagementPanel(client, sessionToken));
        }
        add(tabs, BorderLayout.CENTER);
    }

    void openMyLoans() {
        int index = LibraryModuleNavigation.openMyLoansIndex(tabTitles());
        if (index >= 0) {
            tabs.setSelectedIndex(index);
        }
    }

    private JPanel createHeading(Runnable backToWorkspace) {
        JPanel heading = new JPanel(new BorderLayout(16, 0));
        heading.setOpaque(false);

        JPanel titles = new JPanel(new BorderLayout());
        titles.setOpaque(false);
        JLabel title = new JLabel("图书馆");
        title.setForeground(Theme.TEXT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));
        JLabel subtitle = new JLabel("馆藏检索、借阅流通与库存管理");
        subtitle.setForeground(Theme.MUTED);
        titles.add(title, BorderLayout.NORTH);
        titles.add(subtitle, BorderLayout.SOUTH);
        heading.add(titles, BorderLayout.WEST);

        if (backToWorkspace != null) {
            JButton back = new JButton("← 返回工作台");
            Theme.styleQuietButton(back);
            back.setForeground(Theme.TEXT);
            back.setFont(back.getFont().deriveFont(Font.BOLD));
            back.addActionListener(event -> backToWorkspace.run());
            heading.add(back, BorderLayout.EAST);
        }
        return heading;
    }

    private List<String> tabTitles() {
        List<String> titles = new ArrayList<>(tabs.getTabCount());
        for (int index = 0; index < tabs.getTabCount(); index++) {
            titles.add(tabs.getTitleAt(index));
        }
        return titles;
    }
}
