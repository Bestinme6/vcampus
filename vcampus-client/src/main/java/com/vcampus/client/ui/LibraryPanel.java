package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.protocol.ResponseMessage;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.*;
import java.util.concurrent.*;

abstract class LibraryPanel extends JPanel {
    protected final VCampusClient client; protected final String sessionToken;
    private final java.util.List<AbstractButton> mutationButtons=new ArrayList<>(); private boolean busy;
    LibraryPanel(VCampusClient client,String token){this.client=client;this.sessionToken=token;setBackground(Theme.BACKGROUND);}
    protected JPanel toolbar(){JPanel p=new RoundedPanel(Theme.SURFACE_HOVER,14);p.setLayout(new FlowLayout(FlowLayout.LEFT,10,10));p.setBorder(BorderFactory.createEmptyBorder(2,4,2,4));return p;}
    protected JTextField field(int columns){JTextField f=new JTextField(columns);Theme.styleField(f);return f;}
    protected JButton quiet(String text){JButton b=new JButton(text);Theme.styleQuietButton(b);return b;}
    protected JButton primary(String text){JButton b=new JButton(text);Theme.styleDarkTextPrimaryButton(b);return b;}
    protected JButton mutation(String text){JButton b=primary(text);mutationButtons.add(b);return b;}
    protected void styleTable(JTable t){t.setRowHeight(36);t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);t.setShowVerticalLines(false);t.setGridColor(Theme.BORDER);t.setSelectionBackground(Theme.SECONDARY);t.setFillsViewportHeight(true);DefaultTableCellRenderer h=new DefaultTableCellRenderer();h.setOpaque(true);h.setBackground(Theme.HEADER);h.setForeground(Theme.TEXT);h.setFont(t.getFont().deriveFont(Font.BOLD));h.setBorder(BorderFactory.createEmptyBorder(9,10,9,10));t.getTableHeader().setDefaultRenderer(h);}
    protected JScrollPane scroll(JTable t){JScrollPane s=new JScrollPane(t);s.setBorder(BorderFactory.createLineBorder(Theme.BORDER));return s;}
    protected void runRequest(RequestCall call,ResponseConsumer consumer){if(busy)return;setBusy(true);CompletableFuture.supplyAsync(()->{try{return call.execute();}catch(Exception e){throw new CompletionException(e);}}).whenComplete((r,e)->SwingUtilities.invokeLater(()->{setBusy(false);if(e!=null)error(root(e).getMessage());else if(!r.success())error(r.message());else try{consumer.accept(r);}catch(RuntimeException x){error(x.getMessage());}}));}
    protected void info(String m){UiDialogs.showSuccess(this,m);} protected void error(String m){JOptionPane.showMessageDialog(this,m==null?"请求失败":m,"操作失败",JOptionPane.ERROR_MESSAGE);}protected int selected(JTable t){int r=t.getSelectedRow();if(r<0)error("请先选择一条记录");return r;}
    private void setBusy(boolean value){busy=value;setCursor(Cursor.getPredefinedCursor(value?Cursor.WAIT_CURSOR:Cursor.DEFAULT_CURSOR));mutationButtons.forEach(b->b.setEnabled(!value));}
    private Throwable root(Throwable e){while(e.getCause()!=null)e=e.getCause();return e;}
    @FunctionalInterface protected interface RequestCall{ResponseMessage execute()throws Exception;}@FunctionalInterface protected interface ResponseConsumer{void accept(ResponseMessage response);}
}
