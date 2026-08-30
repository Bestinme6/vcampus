package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.LibraryCopyStatus;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LibraryInventoryPanel extends LibraryPanel {
    private final DefaultTableModel booksModel = model(
            "书目编号", "书名", "作者", "ISBN", "分类", "启用", "可借/总藏");
    private final DefaultTableModel copiesModel = model(
            "ID", "条码", "书名", "书架", "状态", "原因");
    private final JTable books = new JTable(booksModel);
    private final JTable copies = new JTable(copiesModel);
    private final JTextField bookKeyword = field(14);
    private final JTextField copyKeyword = field(14);
    private final JLabel bookPageLabel = new JLabel();
    private final JLabel copyPageLabel = new JLabel();
    private final LibraryAdminPaging bookPaging = new LibraryAdminPaging();
    private final LibraryAdminPaging copyPaging = new LibraryAdminPaging();
    private JButton bookPrevious;
    private JButton bookNext;
    private JButton copyPrevious;
    private JButton copyNext;
    private List<LibraryViewData.CatalogRow> bookRows = List.of();
    private List<LibraryViewData.CopyRow> copyRows = List.of();

    LibraryInventoryPanel(VCampusClient client, String sessionToken) {
        super(client, sessionToken);
        setLayout(new BorderLayout());
        JTabbedPane inner = new JTabbedPane();
        inner.addTab("书目", booksPanel());
        inner.addTab("实体馆藏", copiesPanel());
        inner.addChangeListener(event -> {
            if (inner.getSelectedIndex() == 0) loadBooks();
            else loadCopies();
        });
        add(inner);
        loadBooks(this::loadCopies);
    }

    private JPanel booksPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setOpaque(false);
        LibraryCatalogToolbar toolbar = LibraryCatalogToolbar.create(this, bookKeyword);
        JPanel bar = toolbar.panel();
        JButton search = toolbar.search();
        JButton create = toolbar.create();
        JButton addCopy = toolbar.addCopy();
        JButton edit = toolbar.edit();
        JButton toggle = toolbar.toggle();
        styleTable(books);
        panel.add(bar, BorderLayout.NORTH);
        panel.add(scroll(books), BorderLayout.CENTER);
        bookPrevious = quiet("上一页");
        bookNext = quiet("下一页");
        panel.add(pager(bookPaging, bookPageLabel, this::loadBooks,
                bookPrevious, bookNext), BorderLayout.SOUTH);
        search.addActionListener(event -> {
            bookPaging.reset();
            loadBooks();
        });
        create.addActionListener(event -> bookDialog(null));
        addCopy.addActionListener(event -> {
            int index = selected(books);
            if (index >= 0) copyDialog(bookRows.get(index));
        });
        edit.addActionListener(event -> {
            int index = selected(books);
            if (index >= 0) bookDialog(bookRows.get(index));
        });
        toggle.addActionListener(event -> toggleBook());
        return panel;
    }

    private JPanel copiesPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setOpaque(false);
        JPanel bar = toolbar();
        bar.add(new JLabel("条码 / 书名"));
        bar.add(copyKeyword);
        JButton search = primary("查询");
        JButton status = mutation("变更状态");
        bar.add(search);
        bar.add(status);
        styleTable(copies);
        panel.add(bar, BorderLayout.NORTH);
        panel.add(scroll(copies), BorderLayout.CENTER);
        copyPrevious = quiet("上一页");
        copyNext = quiet("下一页");
        panel.add(pager(copyPaging, copyPageLabel, this::loadCopies,
                copyPrevious, copyNext), BorderLayout.SOUTH);
        search.addActionListener(event -> {
            copyPaging.reset();
            loadCopies();
        });
        status.addActionListener(event -> statusDialog());
        return panel;
    }

    private JPanel pager(LibraryAdminPaging paging, JLabel label, Runnable refresh,
                         JButton previous, JButton next) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setOpaque(false);
        previous.setEnabled(false);
        next.setEnabled(false);
        panel.add(previous);
        panel.add(label);
        panel.add(next);
        previous.addActionListener(event -> {
            paging.previous();
            refresh.run();
        });
        next.addActionListener(event -> {
            paging.next();
            refresh.run();
        });
        return panel;
    }

    private void loadBooks() {
        loadBooks(null);
    }

    private void loadBooks(Runnable afterLoad) {
        String keyword = bookKeyword.getText().trim();
        int pageNumber = bookPaging.page();
        runRequest(() -> client.searchLibraryCatalog(sessionToken,
                keyword, "", pageNumber, true, true), response -> {
            var page = LibraryViewData.catalogPage(response);
            bookPaging.update(page.page(), page.pageSize(), page.total());
            bookPageLabel.setText(bookPaging.label());
            bookPrevious.setEnabled(bookPaging.canPrevious());
            bookNext.setEnabled(bookPaging.canNext());
            bookRows = page.rows();
            booksModel.setRowCount(0);
            for (var row : bookRows) {
                booksModel.addRow(new Object[]{row.catalogCode(), row.title(), row.authors(),
                        row.isbn().isBlank() ? "—" : row.isbn(), row.category(),
                        row.enabled() ? "是" : "否",
                        row.availableCopies() + " / " + row.totalCopies()});
            }
            if (afterLoad != null) afterLoad.run();
        });
    }

    private void loadCopies() {
        loadCopies(null);
    }

    private void loadCopies(Runnable afterLoad) {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("keyword", copyKeyword.getText().trim());
        filters.put("page", Integer.toString(copyPaging.page()));
        filters.put("newestFirst", "true");
        runRequest(() -> client.searchLibraryCopies(sessionToken, filters), response -> {
            var page = LibraryViewData.copyPage(response);
            copyPaging.update(page.page(), page.pageSize(), page.total());
            copyPageLabel.setText(copyPaging.label());
            copyPrevious.setEnabled(copyPaging.canPrevious());
            copyNext.setEnabled(copyPaging.canNext());
            copyRows = page.rows();
            copiesModel.setRowCount(0);
            for (var row : copyRows) {
                copiesModel.addRow(new Object[]{row.copyId(), row.barcode(), row.title(),
                        row.shelfLocation(), row.status(), row.statusReason()});
            }
            if (afterLoad != null) afterLoad.run();
        });
    }

    private void bookDialog(LibraryViewData.CatalogRow row) {
        JTextField isbn = new JTextField(row == null ? "" : row.isbn());
        JTextField title = new JTextField(row == null ? "" : row.title());
        JTextField authors = new JTextField(row == null ? "" : row.authors());
        JTextField publisher = new JTextField(row == null ? "" : row.publisher());
        JTextField year = new JTextField(row == null || row.publishYear() == null
                ? "" : row.publishYear().toString());
        JTextField category = new JTextField(row == null ? "" : row.category());
        JTextArea description = new JTextArea(row == null ? "" : row.description(), 4, 28);
        Object[] form = {"ISBN（选填）", isbn, "书名", title, "作者", authors,
                "出版社", publisher, "出版年", year, "分类", category,
                "简介", new JScrollPane(description)};
        if (JOptionPane.showConfirmDialog(this, form,
                row == null ? "新建书目" : "编辑书目",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        Map<String, String> values = new LinkedHashMap<>();
        values.put("isbn", isbn.getText());
        values.put("title", title.getText());
        values.put("authors", authors.getText());
        values.put("publisher", publisher.getText());
        values.put("publishYear", year.getText());
        values.put("category", category.getText());
        values.put("description", description.getText());
        if (row != null) values.put("bookId", Long.toString(row.bookId()));
        runRequest(() -> row == null
                ? client.createLibraryBook(sessionToken, values)
                : client.updateLibraryBook(sessionToken, values), response -> {
            if (row == null) {
                bookPaging.reset();
                info("创建成功，书目编号：" + response.data().get("catalogCode"));
            } else {
                info(response.message());
            }
            loadBooks();
        });
    }

    private void toggleBook() {
        int index = selected(books);
        if (index < 0) return;
        var row = bookRows.get(index);
        runRequest(() -> client.setLibraryBookEnabled(
                sessionToken, row.bookId(), !row.enabled()), response -> {
            info(response.message());
            loadBooks();
        });
    }

    private void copyDialog(LibraryViewData.CatalogRow book) {
        JTextField shelf = new JTextField();
        Object[] form = {"书目", book.catalogCode() + " · " + book.title(),
                "馆藏条码", "由服务器自动生成", "书架位置", shelf};
        if (JOptionPane.showConfirmDialog(this, form, "新增馆藏",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        Map<String, String> values = Map.of(
                "bookId", Long.toString(book.bookId()),
                "barcode", "",
                "shelfLocation", shelf.getText());
        runRequest(() -> client.createLibraryCopy(sessionToken, values), response -> {
            copyPaging.reset();
            info("馆藏创建成功，条码：" + response.data().get("barcode"));
            loadCopies(this::loadBooks);
        });
    }

    private void statusDialog() {
        int index = selected(copies);
        if (index < 0) return;
        var row = copyRows.get(index);
        if (row.status() == LibraryCopyStatus.ON_LOAN) {
            error("借出中的馆藏不能手工变更状态");
            return;
        }
        JComboBox<LibraryCopyStatus> status = new JComboBox<>(new LibraryCopyStatus[]{
                LibraryCopyStatus.AVAILABLE, LibraryCopyStatus.LOST,
                LibraryCopyStatus.DAMAGED, LibraryCopyStatus.WITHDRAWN});
        JTextField reason = new JTextField();
        if (JOptionPane.showConfirmDialog(this,
                new Object[]{"新状态", status, "原因", reason}, "变更馆藏状态",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        LibraryCopyStatus value = (LibraryCopyStatus) status.getSelectedItem();
        if (value != LibraryCopyStatus.AVAILABLE && reason.getText().isBlank()) {
            error("该状态必须填写原因");
            return;
        }
        String statusReason = reason.getText().trim();
        runRequest(() -> client.setLibraryCopyStatus(sessionToken, row.copyId(),
                value.name(), statusReason), response -> {
            info(response.message());
            loadCopies(this::loadBooks);
        });
    }

    private DefaultTableModel model(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }
}
