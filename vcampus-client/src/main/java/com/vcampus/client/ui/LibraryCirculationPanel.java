package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.LibraryCirculationOperation;
import com.vcampus.common.model.LibraryReturnCondition;

import javax.swing.*;
import java.awt.*;

final class LibraryCirculationPanel extends LibraryPanel {
    private final JTextField username = field(14);
    private final JTextField barcode = field(14);
    private final JTextField reason = field(20);
    private final JComboBox<LibraryCirculationOperation> operation =
            new JComboBox<>(LibraryCirculationOperation.values());
    private final JComboBox<LibraryReturnCondition> condition =
            new JComboBox<>(LibraryReturnCondition.values());
    private final JTextArea preview = new JTextArea();
    private final JButton execute = mutation("办理");
    private LibraryViewData.BorrowerPreview current;

    LibraryCirculationPanel(VCampusClient client, String sessionToken) {
        super(client, sessionToken);
        setLayout(new BorderLayout(0, 16));
        JPanel bar = toolbar();
        bar.add(new JLabel("用户名"));
        bar.add(username);
        bar.add(new JLabel("馆藏条码"));
        bar.add(barcode);
        bar.add(new JLabel("业务"));
        bar.add(operation);
        JButton check = primary("校验信息");
        bar.add(check);
        add(bar, BorderLayout.NORTH);

        preview.setEditable(false);
        preview.setFont(preview.getFont().deriveFont(16f));
        preview.setBorder(BorderFactory.createEmptyBorder(24, 28, 24, 28));
        preview.setBackground(Theme.SURFACE_HOVER);
        add(new JScrollPane(preview), BorderLayout.CENTER);

        condition.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof LibraryReturnCondition returnCondition) {
                    setText(LibraryReturnFormPolicy.label(returnCondition));
                }
                return this;
            }
        });
        JPanel action = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));
        action.setOpaque(false);
        action.add(new JLabel("归还状态"));
        action.add(condition);
        action.add(new JLabel("原因"));
        action.add(reason);
        execute.setEnabled(false);
        action.add(execute);
        add(action, BorderLayout.SOUTH);

        check.addActionListener(event -> validateInput());
        execute.addActionListener(event -> execute());
        operation.addActionListener(event -> {
            current = null;
            execute.setEnabled(false);
        });
    }

    private void validateInput() {
        String usernameValue = username.getText().trim();
        String barcodeValue = barcode.getText().trim();
        LibraryCirculationOperation operationValue =
                (LibraryCirculationOperation) operation.getSelectedItem();
        runRequest(() -> client.previewLibraryCirculation(
                sessionToken, usernameValue, barcodeValue, operationValue), response -> {
            current = LibraryViewData.borrowerPreview(response);
            preview.setText("借阅人：" + current.displayName() + "（" + current.username()
                    + "）\n基础身份：" + current.baseIdentity()
                    + "\n当前借阅：" + current.activeLoans() + " / " + current.maxLoans()
                    + "\n逾期：" + (current.overdue() ? "是" : "否")
                    + "\n\n图书：《" + current.title() + "》"
                    + "\n条码：" + current.barcode()
                    + "\n馆藏状态：" + current.copyStatus()
                    + "\n\n校验结果：" + current.message());
            execute.setEnabled(current.allowed());
        });
    }

    private void execute() {
        if (current == null || !current.allowed()) return;
        if (operation.getSelectedItem() == LibraryCirculationOperation.BORROW) {
            executeBorrow();
        } else {
            executeReturn();
        }
    }

    private void executeBorrow() {
        if (JOptionPane.showConfirmDialog(this,
                "确认替 " + current.displayName() + " 借出《" + current.title() + "》？",
                "办理借阅", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        String usernameValue = current.username();
        String barcodeValue = current.barcode();
        runRequest(() -> client.adminBorrowLibraryCopy(
                sessionToken, usernameValue, barcodeValue), response -> {
            info("借阅办理成功");
            validateInput();
        });
    }

    private void executeReturn() {
        LibraryReturnCondition value = (LibraryReturnCondition) condition.getSelectedItem();
        String reasonValue = reason.getText().trim();
        if (LibraryReturnFormPolicy.requiresReason(value) && reasonValue.isBlank()) {
            error(LibraryReturnFormPolicy.missingReasonMessage(value));
            return;
        }
        if (JOptionPane.showConfirmDialog(this, LibraryReturnFormPolicy.confirmation(value),
                "办理归还", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        String barcodeValue = current.barcode();
        runRequest(() -> client.adminReturnLibraryCopy(
                sessionToken, barcodeValue, value, reasonValue), response -> {
            info(value == LibraryReturnCondition.DAMAGED ? "破损归还办理成功" : "归还办理成功");
            validateInput();
        });
    }
}
