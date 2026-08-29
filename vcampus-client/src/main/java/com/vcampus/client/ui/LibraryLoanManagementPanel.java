package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.*;

final class LibraryLoanManagementPanel extends LibraryPanel {
    private final JTextField keyword=field(16);private final JComboBox<String>active=new JComboBox<>(new String[]{"全部状态","借阅中","已归还"}),overdue=new JComboBox<>(new String[]{"全部逾期状态","已逾期","未逾期"});private final DefaultTableModel model=new DefaultTableModel(new Object[]{"借阅人","图书","条码","借阅时间","应还时间","归还时间","渠道","状态"},0){public boolean isCellEditable(int r,int c){return false;}};private final JTable table=new JTable(model);private final JLabel pageLabel=new JLabel("第 1 页");private int page=1,total;
    LibraryLoanManagementPanel(VCampusClient c,String t){super(c,t);setLayout(new BorderLayout(0,14));JPanel bar=toolbar();bar.add(new JLabel("用户 / 图书 / 条码"));bar.add(keyword);bar.add(active);bar.add(overdue);JButton search=primary("查询");bar.add(search);add(bar,BorderLayout.NORTH);styleTable(table);add(scroll(table));JPanel foot=new JPanel(new FlowLayout(FlowLayout.LEFT));foot.setOpaque(false);JButton prev=quiet("上一页"),next=quiet("下一页");foot.add(prev);foot.add(pageLabel);foot.add(next);add(foot,BorderLayout.SOUTH);search.addActionListener(e->{page=1;refresh();});prev.addActionListener(e->{if(page>1){page--;refresh();}});next.addActionListener(e->{if(page*10<total){page++;refresh();}});refresh();}
    private void refresh(){Map<String,String>f=new LinkedHashMap<>();f.put("keyword",keyword.getText().trim());f.put("page",Integer.toString(page));if(active.getSelectedIndex()>0)f.put("active",Boolean.toString(active.getSelectedIndex()==1));if(overdue.getSelectedIndex()>0)f.put("overdue",Boolean.toString(overdue.getSelectedIndex()==1));runRequest(()->client.searchLibraryLoans(sessionToken,f),r->{var p=LibraryViewData.loanPage(r);total=p.total();model.setRowCount(0);for(var x:p.rows())model.addRow(new Object[]{x.borrowerDisplayName()+" / "+x.borrowerUsername(),x.title(),x.barcode(),x.borrowedAt(),x.dueAt(),x.returnedAt()==null?"—":x.returnedAt(),x.channel(),x.returnedAt()!=null?"已归还":x.overdue()?"逾期":"借阅中"});pageLabel.setText("第 "+p.page()+" 页 · 共 "+p.total()+" 条");});}
}
