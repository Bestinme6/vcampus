package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.*;
import java.util.List;

final class MyLibraryLoansPanel extends LibraryPanel {
    private final JComboBox<String>scope=new JComboBox<>(new String[]{"当前借阅","即将到期","已经逾期","历史记录"});private final DefaultTableModel model=new DefaultTableModel(new Object[]{"书名","条码","借阅时间","应还时间","状态","续借"},0){public boolean isCellEditable(int r,int c){return false;}};private final JTable table=new JTable(model);private final JLabel summary=new JLabel(" ");private java.util.List<LibraryViewData.LoanRow>rows=List.of();private int page=1;
    MyLibraryLoansPanel(VCampusClient c,String t){super(c,t);setLayout(new BorderLayout(0,14));JPanel bar=toolbar();bar.add(new JLabel("范围"));bar.add(scope);JButton refresh=primary("刷新");bar.add(refresh);bar.add(Box.createHorizontalStrut(12));bar.add(summary);add(bar,BorderLayout.NORTH);styleTable(table);add(scroll(table),BorderLayout.CENTER);JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT));actions.setOpaque(false);JButton renew=mutation("续借"),returned=mutation("确认归还");actions.add(renew);actions.add(returned);add(actions,BorderLayout.SOUTH);scope.addActionListener(e->{page=1;refresh();});refresh.addActionListener(e->refresh());renew.addActionListener(e->renew());returned.addActionListener(e->returned());refresh();}
    void refresh(){String s=switch(scope.getSelectedIndex()){case 0->"active";case 1->"active";case 2->"overdue";default->"history";};runRequest(()->client.myLibraryLoans(sessionToken,s,page),r->{var p=LibraryViewData.loanPage(r);rows=p.rows();if(scope.getSelectedIndex()==1)rows=rows.stream().filter(x->!x.overdue()&&x.dueAt().isBefore(java.time.Instant.now().plus(java.time.Duration.ofDays(3)))).toList();model.setRowCount(0);for(var x:rows)model.addRow(new Object[]{x.title(),x.barcode(),x.borrowedAt(),x.dueAt(),x.returnedAt()!=null?"已归还":x.overdue()?"逾期":"借阅中",x.renewable()?"可续借":"—"});summary.setText("借阅上限 "+p.maxLoans()+" 本 · 借期 "+p.initialLoanDays()+" 天 · 续借 "+p.renewalDays()+" 天");});}
    private void renew(){int i=selected(table);if(i<0)return;var x=rows.get(i);if(!x.renewable()){error("该记录当前不可续借");return;}if(JOptionPane.showConfirmDialog(this,"确认续借《"+x.title()+"》？","确认续借",JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION)runRequest(()->client.renewLibraryLoan(sessionToken,x.loanId()),r->{info("续借成功，新到期时间："+LibraryViewData.receipt(r).dueAt());refresh();});}
    private void returned(){int i=selected(table);if(i<0)return;var x=rows.get(i);if(x.returnedAt()!=null){error("该图书已经归还");return;}if(JOptionPane.showConfirmDialog(this,"确认归还《"+x.title()+"》？","确认归还",JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION)runRequest(()->client.returnLibraryLoan(sessionToken,x.loanId()),r->{info("归还成功");refresh();});}
}
