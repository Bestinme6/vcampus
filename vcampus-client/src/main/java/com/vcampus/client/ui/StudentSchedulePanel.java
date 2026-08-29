package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.client.ui.AcademicViewData.TermOption;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;

final class StudentSchedulePanel extends AcademicPanel {
    private final JComboBox<TermOption> term = new JComboBox<>();
    private final ScheduleGridPanel scheduleGrid = new ScheduleGridPanel(false);

    StudentSchedulePanel(VCampusClient client, String sessionToken) {
        super(client, sessionToken);
        setLayout(new BorderLayout(0, 14));
        setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        JPanel header = new JPanel(new BorderLayout(10, 0));
        header.setOpaque(false);
        term.setPreferredSize(new Dimension(300, 40));
        JButton refresh = primaryButton("刷新课表");
        refresh.addActionListener(event -> loadSchedule());
        header.add(term, BorderLayout.WEST);
        header.add(refresh, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
        add(scheduleGrid, BorderLayout.CENTER);
        SwingUtilities.invokeLater(this::loadReferences);
    }

    private void loadReferences() {
        runRequest(() -> client.academicReferenceData(sessionToken), response -> {
            term.removeAllItems();
            AcademicViewData.references(response).terms().forEach(term::addItem);
            if (term.getItemCount() > 0) {
                loadSchedule();
            }
        });
    }

    private void loadSchedule() {
        TermOption selected = (TermOption) term.getSelectedItem();
        if (selected == null) {
            return;
        }
        runRequest(() -> client.mySchedule(sessionToken, selected.id()), response -> {
            scheduleGrid.setEntries(AcademicViewData.schedules(response));
        });
    }
}
