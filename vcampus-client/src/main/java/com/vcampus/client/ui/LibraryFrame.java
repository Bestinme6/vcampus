package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.UserRole;

import javax.swing.JFrame;
import java.awt.Dimension;
import java.util.Set;

public final class LibraryFrame extends JFrame {
    private final LibraryModulePanel modulePanel;

    public LibraryFrame(VCampusClient client, String token, Set<UserRole> roles) {
        super("VCampus · 图书馆");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(1100, 700));
        setSize(1320, 800);
        setLocationRelativeTo(null);
        modulePanel = new LibraryModulePanel(client, token, roles, null);
        setContentPane(modulePanel);
    }

    public void openMyLoans() {
        modulePanel.openMyLoans();
    }
}
