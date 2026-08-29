package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.ForumSort;
import com.vcampus.common.protocol.ResponseMessage;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

final class ForumHomePanel extends JPanel {
    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final VCampusClient client;
    private final String sessionToken;
    private final java.util.function.LongConsumer openPost;
    private final Consumer<ForumNavigation.HomeQuery> rememberQuery;
    private final JComboBox<SectionChoice> section = new JComboBox<>();
    private final JTextField keyword = new JTextField();
    private final JComboBox<SortChoice> sort = new JComboBox<>(SortChoice.values());
    private final DefaultListModel<ForumViewData.PostRow> posts = new DefaultListModel<>();
    private final JList<ForumViewData.PostRow> postList = new JList<>(posts);
    private final JLabel pageLabel = new JLabel("第 1 页");
    private final JButton previous = new JButton("上一页");
    private final JButton next = new JButton("下一页");
    private final JButton search = new JButton("搜索");
    private final AtomicLong generation = new AtomicLong();
    private List<ForumViewData.SectionRow> sections = List.of();
    private int page = 1;
    private int total;

    ForumHomePanel(VCampusClient client, String sessionToken,
                   java.util.function.LongConsumer openPost,
                   Consumer<ForumNavigation.HomeQuery> rememberQuery) {
        this.client = client;
        this.sessionToken = sessionToken;
        this.openPost = openPost;
        this.rememberQuery = rememberQuery;
        setLayout(new BorderLayout(0, 14));
        setOpaque(false);
        add(toolbar(), BorderLayout.NORTH);
        configureList();
        JScrollPane scroll = new JScrollPane(postList);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        add(scroll, BorderLayout.CENTER);
        add(pager(), BorderLayout.SOUTH);
    }

    void activate(ForumNavigation.HomeQuery query) {
        keyword.setText(query.keyword());
        sort.setSelectedItem(SortChoice.of(query.sort()));
        page = query.page();
        if (sections.isEmpty()) loadSections(query.sectionId());
        else {
            selectSection(query.sectionId());
            loadPosts();
        }
    }

    private JComponent toolbar() {
        RoundedPanel panel = new RoundedPanel(Theme.SURFACE, 16);
        panel.setLayout(new FlowLayout(FlowLayout.LEFT, 10, 12));
        section.setPreferredSize(new Dimension(150, 36));
        keyword.setPreferredSize(new Dimension(240, 36));
        keyword.setToolTipText("搜索帖子标题或正文");
        sort.setPreferredSize(new Dimension(130, 36));
        Theme.styleField(keyword);
        Theme.styleDarkTextPrimaryButton(search);
        search.addActionListener(event -> { page = 1; loadPosts(); });
        JButton refresh = new JButton("刷新");
        Theme.styleQuietButton(refresh);
        refresh.addActionListener(event -> loadPosts());
        JButton publish = new JButton("发布帖子");
        Theme.styleDarkTextPrimaryButton(publish);
        publish.addActionListener(event -> publish());
        panel.add(section);
        panel.add(keyword);
        panel.add(sort);
        panel.add(search);
        panel.add(refresh);
        panel.add(publish);
        return panel;
    }

    private void configureList() {
        postList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        postList.setFixedCellHeight(88);
        postList.setCellRenderer(new PostRenderer());
        postList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && postList.getSelectedValue() != null) {
                    openPost.accept(postList.getSelectedValue().id());
                }
            }
        });
    }

    private JComponent pager() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        panel.setOpaque(false);
        Theme.styleQuietButton(previous);
        Theme.styleQuietButton(next);
        previous.addActionListener(event -> { if (page > 1) { page--; loadPosts(); } });
        next.addActionListener(event -> { if (page * 10 < total) { page++; loadPosts(); } });
        panel.add(pageLabel);
        panel.add(previous);
        panel.add(next);
        return panel;
    }

    private void loadSections(Long selectedId) {
        setBusy(true);
        ForumAsync.run(() -> client.listForumSections(sessionToken, false), response -> {
            setBusy(false);
            if (!response.success()) { showFailure(response); return; }
            sections = ForumViewData.sections(response);
            DefaultComboBoxModel<SectionChoice> model = new DefaultComboBoxModel<>();
            model.addElement(new SectionChoice(null, "全部板块"));
            sections.forEach(value -> model.addElement(new SectionChoice(value.id(), value.name())));
            section.setModel(model);
            selectSection(selectedId);
            loadPosts();
        }, this::showError);
    }

    private void loadPosts() {
        long request = generation.incrementAndGet();
        setBusy(true);
        ForumNavigation.HomeQuery query = currentQuery();
        rememberQuery.accept(query);
        ForumAsync.run(() -> client.searchForumPosts(
                sessionToken, query.sectionId(), query.keyword(), query.sort(), query.page()),
                response -> {
                    if (request != generation.get()) return;
                    setBusy(false);
                    if (!response.success()) { showFailure(response); return; }
                    ForumViewData.PostPage result = ForumViewData.postPage(response);
                    posts.clear();
                    result.rows().forEach(posts::addElement);
                    total = result.total();
                    pageLabel.setText("第 " + result.page() + " 页 · 共 " + total + " 条");
                    previous.setEnabled(page > 1);
                    next.setEnabled(page * result.pageSize() < total);
                }, error -> { if (request == generation.get()) showError(error); });
    }

    private ForumNavigation.HomeQuery currentQuery() {
        SectionChoice choice = (SectionChoice) section.getSelectedItem();
        SortChoice selectedSort = (SortChoice) sort.getSelectedItem();
        return new ForumNavigation.HomeQuery(choice == null ? null : choice.id(),
                keyword.getText().strip(), selectedSort == null
                ? ForumSort.LATEST_REPLY : selectedSort.value(), page);
    }

    private void publish() {
        if (sections.isEmpty()) {
            JOptionPane.showMessageDialog(this, "当前没有可发帖板块");
            return;
        }
        JComboBox<SectionChoice> board = new JComboBox<>();
        sections.forEach(value -> board.addItem(new SectionChoice(value.id(), value.name())));
        JTextField title = new JTextField();
        JTextArea content = new JTextArea(8, 42);
        content.setLineWrap(true);
        content.setWrapStyleWord(true);
        JPanel form = new JPanel(new BorderLayout(0, 8));
        JPanel top = new JPanel(new GridLayout(2, 2, 8, 8));
        top.add(new JLabel("板块")); top.add(board);
        top.add(new JLabel("标题")); top.add(title);
        form.add(top, BorderLayout.NORTH);
        form.add(new JScrollPane(content), BorderLayout.CENTER);
        int result = JOptionPane.showConfirmDialog(this, form, "发布帖子",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;
        SectionChoice choice = (SectionChoice) board.getSelectedItem();
        if (choice == null) return;
        setBusy(true);
        ForumAsync.run(() -> client.createForumPost(
                sessionToken, Objects.requireNonNull(choice.id()),
                title.getText(), content.getText()), response -> {
            setBusy(false);
            if (!response.success()) { showFailure(response); return; }
            page = 1;
            loadPosts();
        }, this::showError);
    }

    private void selectSection(Long id) {
        for (int index = 0; index < section.getItemCount(); index++) {
            if (Objects.equals(section.getItemAt(index).id(), id)) {
                section.setSelectedIndex(index);
                return;
            }
        }
        section.setSelectedIndex(0);
    }

    private void setBusy(boolean busy) {
        search.setEnabled(!busy);
    }

    private void showFailure(ResponseMessage response) {
        JOptionPane.showMessageDialog(this, response.message(), "校园论坛",
                JOptionPane.WARNING_MESSAGE);
    }

    private void showError(Throwable error) {
        setBusy(false);
        JOptionPane.showMessageDialog(this, "无法连接论坛服务：" + error.getMessage(),
                "校园论坛", JOptionPane.ERROR_MESSAGE);
    }

    private record SectionChoice(Long id, String label) {
        @Override public String toString() { return label; }
    }

    private enum SortChoice {
        LATEST_REPLY("最新回复", ForumSort.LATEST_REPLY),
        LATEST_CREATED("最新发布", ForumSort.LATEST_CREATED);
        private final String label;
        private final ForumSort value;
        SortChoice(String label, ForumSort value) { this.label = label; this.value = value; }
        ForumSort value() { return value; }
        static SortChoice of(ForumSort value) {
            return value == ForumSort.LATEST_CREATED ? LATEST_CREATED : LATEST_REPLY;
        }
        @Override public String toString() { return label; }
    }

    private static final class PostRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean selected, boolean focus) {
            JPanel panel = new JPanel(new BorderLayout(12, 6));
            panel.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
            panel.setBackground(selected ? Theme.SECONDARY : Theme.SURFACE);
            ForumViewData.PostRow row = (ForumViewData.PostRow) value;
            String badges = (row.pinned() ? "[置顶] " : "")
                    + (row.featured() ? "[精华] " : "") + "[" + row.sectionName() + "] ";
            JLabel title = new JLabel(badges + row.title());
            title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
            title.setForeground(Theme.TEXT);
            JLabel summary = new JLabel("<html>" + escape(row.summary()).replace("\n", " ") + "</html>");
            summary.setForeground(Theme.MUTED);
            JLabel meta = new JLabel(row.authorDisplayName() + " · "
                    + TIME.format(row.createdAt()) + " · " + row.viewCount()
                    + " 浏览 · " + row.commentCount() + " 评论");
            meta.setForeground(Theme.MUTED);
            panel.add(title, BorderLayout.NORTH);
            panel.add(summary, BorderLayout.CENTER);
            panel.add(meta, BorderLayout.SOUTH);
            return panel;
        }

        private static String escape(String value) {
            return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
