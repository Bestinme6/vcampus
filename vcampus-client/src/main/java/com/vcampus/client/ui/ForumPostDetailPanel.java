package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.ForumContentStatus;
import com.vcampus.common.protocol.ResponseMessage;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

final class ForumPostDetailPanel extends JPanel {
    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final VCampusClient client;
    private final String sessionToken;
    private final Runnable back;
    private final JLabel title = new JLabel("帖子详情");
    private final JLabel meta = new JLabel();
    private final JTextArea content = new JTextArea();
    private final DefaultListModel<ForumViewData.CommentRow> comments = new DefaultListModel<>();
    private final JList<ForumViewData.CommentRow> commentList = new JList<>(comments);
    private final JTextArea reply = new JTextArea(3, 30);
    private final JButton send = new JButton("发表评论");
    private final JButton deletePost = new JButton("删除帖子");
    private final JButton deleteComment = new JButton("删除所选评论");
    private final AtomicLong generation = new AtomicLong();
    private long postId;
    private int commentPage = 1;

    ForumPostDetailPanel(VCampusClient client, String sessionToken, Runnable back) {
        this.client = client;
        this.sessionToken = sessionToken;
        this.back = back;
        setLayout(new BorderLayout(0, 12));
        setOpaque(false);
        add(toolbar(), BorderLayout.NORTH);
        add(body(), BorderLayout.CENTER);
        add(replyPanel(), BorderLayout.SOUTH);
    }

    void open(long postId) {
        this.postId = postId;
        this.commentPage = 1;
        load();
    }

    private JPanel toolbar() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        JButton backButton = new JButton("← 返回帖子列表");
        Theme.styleQuietButton(backButton);
        backButton.addActionListener(event -> back.run());
        deletePost.setForeground(Theme.DANGER);
        deletePost.addActionListener(event -> deletePost());
        panel.add(backButton, BorderLayout.WEST);
        panel.add(deletePost, BorderLayout.EAST);
        return panel;
    }

    private Component body() {
        RoundedPanel post = new RoundedPanel(Theme.SURFACE, 16);
        post.setLayout(new BorderLayout(0, 10));
        post.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 21f));
        title.setForeground(Theme.TEXT);
        meta.setForeground(Theme.MUTED);
        JPanel header = new JPanel(new BorderLayout(0, 5));
        header.setOpaque(false);
        header.add(title, BorderLayout.NORTH);
        header.add(meta, BorderLayout.SOUTH);
        content.setEditable(false);
        content.setLineWrap(true);
        content.setWrapStyleWord(true);
        content.setOpaque(false);
        post.add(header, BorderLayout.NORTH);
        post.add(new JScrollPane(content), BorderLayout.CENTER);

        commentList.setCellRenderer(new CommentRenderer());
        JPanel commentArea = new JPanel(new BorderLayout(0, 8));
        commentArea.setOpaque(false);
        JPanel commentHeader = new JPanel(new BorderLayout());
        commentHeader.setOpaque(false);
        JLabel label = new JLabel("评论");
        label.setFont(label.getFont().deriveFont(Font.BOLD, 16f));
        deleteComment.setForeground(Theme.DANGER);
        deleteComment.addActionListener(event -> deleteSelectedComment());
        commentHeader.add(label, BorderLayout.WEST);
        commentHeader.add(deleteComment, BorderLayout.EAST);
        commentArea.add(commentHeader, BorderLayout.NORTH);
        commentArea.add(new JScrollPane(commentList), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, post, commentArea);
        split.setBorder(null);
        split.setResizeWeight(0.45);
        return split;
    }

    private JPanel replyPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 0));
        panel.setOpaque(false);
        reply.setLineWrap(true);
        reply.setWrapStyleWord(true);
        send.addActionListener(event -> sendComment());
        Theme.styleDarkTextPrimaryButton(send);
        panel.add(new JScrollPane(reply), BorderLayout.CENTER);
        panel.add(send, BorderLayout.EAST);
        return panel;
    }

    private void load() {
        long request = generation.incrementAndGet();
        ForumAsync.run(() -> client.getForumPost(sessionToken, postId), response -> {
            if (request != generation.get()) return;
            if (!response.success()) {
                showUnavailablePost();
                back.run();
                return;
            }
            render(ForumViewData.postDetail(response));
            loadComments(request);
        }, this::showError);
    }

    private void render(ForumViewData.PostDetail post) {
        title.setText((post.pinned() ? "[置顶] " : "")
                + (post.featured() ? "[精华] " : "") + post.title());
        meta.setText(post.sectionName() + " · " + post.authorDisplayName() + " · "
                + TIME.format(post.createdAt()) + " · " + post.viewCount() + " 浏览");
        content.setText(post.content());
        content.setCaretPosition(0);
        deletePost.setVisible(post.canDelete());
        boolean commentable = post.status() == ForumContentStatus.NORMAL && !post.locked();
        reply.setEnabled(commentable);
        send.setEnabled(commentable);
        reply.setToolTipText(commentable ? null : "帖子已锁定，不能继续评论");
    }

    private void loadComments(long request) {
        ForumAsync.run(() -> client.listForumComments(sessionToken, postId, commentPage),
                response -> {
                    if (request != generation.get()) return;
                    if (!response.success()) { showFailure(response); return; }
                    comments.clear();
                    ForumViewData.commentPage(response).rows().forEach(comments::addElement);
                }, this::showError);
    }

    private void sendComment() {
        String value = reply.getText();
        send.setEnabled(false);
        ForumAsync.run(() -> client.createForumComment(sessionToken, postId, value), response -> {
            send.setEnabled(true);
            if (!response.success()) { showFailure(response); return; }
            reply.setText("");
            load();
        }, error -> { send.setEnabled(true); showError(error); });
    }

    private void deletePost() {
        if (JOptionPane.showConfirmDialog(this, "确定删除这篇帖子吗？",
                "删除帖子", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        ForumAsync.run(() -> client.deleteForumPost(sessionToken, postId), response -> {
            if (!response.success()) { showFailure(response); return; }
            back.run();
        }, this::showError);
    }

    private void deleteSelectedComment() {
        ForumViewData.CommentRow selected = commentList.getSelectedValue();
        if (selected == null || !selected.canDelete()) {
            JOptionPane.showMessageDialog(this, "请选择自己发布的评论");
            return;
        }
        ForumAsync.run(() -> client.deleteForumComment(sessionToken, selected.id()), response -> {
            if (!response.success()) { showFailure(response); return; }
            load();
        }, this::showError);
    }

    private void showFailure(ResponseMessage response) {
        JOptionPane.showMessageDialog(this, response.message(), "校园论坛",
                JOptionPane.WARNING_MESSAGE);
    }

    private void showUnavailablePost() {
        JOptionPane.showMessageDialog(this, "该帖子当前不可访问", "校园论坛",
                JOptionPane.WARNING_MESSAGE);
    }

    private void showError(Throwable error) {
        JOptionPane.showMessageDialog(this, "无法连接论坛服务：" + error.getMessage(),
                "校园论坛", JOptionPane.ERROR_MESSAGE);
    }

    private static final class CommentRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean selected, boolean focus) {
            ForumViewData.CommentRow row = (ForumViewData.CommentRow) value;
            JPanel panel = new JPanel(new BorderLayout(8, 5));
            panel.setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 12));
            panel.setBackground(selected ? Theme.SECONDARY : Theme.SURFACE);
            JLabel author = new JLabel(row.authorDisplayName() + " · " + TIME.format(row.createdAt()));
            author.setFont(author.getFont().deriveFont(Font.BOLD));
            JLabel text = new JLabel("<html>" + row.content().replace("&", "&amp;")
                    .replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>") + "</html>");
            text.setForeground(Theme.TEXT);
            panel.add(author, BorderLayout.NORTH);
            panel.add(text, BorderLayout.CENTER);
            return panel;
        }
    }
}
