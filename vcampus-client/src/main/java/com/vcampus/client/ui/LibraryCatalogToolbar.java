package com.vcampus.client.ui;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

record LibraryCatalogToolbar(JPanel panel, JTextField keyword, JButton search,
                             JButton create, JButton addCopy, JButton edit,
                             JButton toggle) {
    static LibraryCatalogToolbar create(LibraryPanel owner, JTextField keyword) {
        JPanel panel = owner.toolbar();
        panel.add(new JLabel("书名 / 编号 / ISBN"));
        panel.add(keyword);
        JButton search = owner.primary("查询");
        JButton create = owner.mutation("新建书目");
        JButton addCopy = owner.mutation("新增馆藏");
        addCopy.setToolTipText("为选中书目新增实体馆藏");
        JButton edit = owner.mutation("编辑");
        JButton toggle = owner.mutation("启用/停用");
        panel.add(search);
        panel.add(create);
        panel.add(addCopy);
        panel.add(edit);
        panel.add(toggle);
        return new LibraryCatalogToolbar(panel, keyword, search, create, addCopy, edit, toggle);
    }
}
