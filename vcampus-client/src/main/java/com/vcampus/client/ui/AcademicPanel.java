package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.protocol.ResponseMessage;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Cursor;
import java.awt.Font;
import java.util.concurrent.CompletableFuture;

abstract class AcademicPanel extends JPanel {
    protected final VCampusClient client;
    protected final String sessionToken;
    private boolean busy;

    AcademicPanel(VCampusClient client, String sessionToken) {
        this.client = client;
        this.sessionToken = sessionToken;
        setBackground(Theme.BACKGROUND);
    }

    protected JButton actionButton(String text) {
        JButton button = new JButton(text);
        Theme.styleQuietButton(button);
        button.setForeground(Theme.TEXT);
        button.setFont(button.getFont().deriveFont(Font.BOLD));
        return button;
    }

    protected JButton primaryButton(String text) {
        JButton button = new JButton(text);
        Theme.styleCommandButton(button);
        return button;
    }

    protected void styleTable(JTable table) {
        table.setRowHeight(34);
        table.setBackground(Theme.SURFACE);
        table.setForeground(Theme.TEXT);
        table.setSelectionBackground(Theme.SECONDARY);
        table.setSelectionForeground(Theme.TEXT);
        table.setGridColor(Theme.BORDER);
        DefaultTableCellRenderer header = new DefaultTableCellRenderer();
        header.setOpaque(true);
        header.setBackground(Theme.HEADER);
        header.setForeground(Theme.TEXT);
        header.setFont(table.getFont().deriveFont(Font.BOLD));
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 1, Theme.BORDER),
                BorderFactory.createEmptyBorder(7, 8, 7, 8)));
        table.getTableHeader().setDefaultRenderer(header);
    }

    protected void runRequest(RequestCall call, ResponseConsumer consumer) {
        if (busy) {
            return;
        }
        setBusy(true);
        CompletableFuture.supplyAsync(() -> {
            try {
                return call.execute();
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }).whenComplete((response, error) -> SwingUtilities.invokeLater(() -> {
            setBusy(false);
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                showError(cause.getMessage() == null ? "请求失败" : cause.getMessage());
            } else if (!response.success()) {
                showError(response.message());
            } else {
                consumer.accept(response);
            }
        }));
    }

    protected void showInfo(String message) {
        UiDialogs.showSuccess(this, message);
    }

    protected void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "操作失败", JOptionPane.ERROR_MESSAGE);
    }

    protected int selectedRow(JTable table) {
        int row = table.getSelectedRow();
        if (row < 0) {
            showError("请先选择一条记录");
        }
        return row;
    }

    private void setBusy(boolean value) {
        busy = value;
        setCursor(Cursor.getPredefinedCursor(value ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    @FunctionalInterface
    protected interface RequestCall {
        ResponseMessage execute() throws Exception;
    }

    @FunctionalInterface
    protected interface ResponseConsumer {
        void accept(ResponseMessage response);
    }
}
