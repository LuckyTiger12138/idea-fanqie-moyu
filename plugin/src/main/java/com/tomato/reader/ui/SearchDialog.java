package com.tomato.reader.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.tomato.reader.api.ApiClient;
import com.tomato.reader.api.TomatoMapper;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 在线模式的搜索选书对话框。 */
public class SearchDialog extends DialogWrapper {

    private final ApiClient api;
    private final JBTextField keyword = new JBTextField();
    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JList<String> list = new JList<>(model);
    private final JLabel hint = new JLabel("输入关键词，回车搜索");

    private List<TomatoMapper.Book> results = new ArrayList<>();
    private TomatoMapper.Book picked;

    public SearchDialog(Project project, ApiClient api) {
        super(project);
        this.api = api;
        setTitle("搜索书籍");
        setOKButtonText("打开");
        init();
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.add(keyword, BorderLayout.CENTER);
        JButton go = new JButton("搜索");
        go.addActionListener(e -> doSearch());
        top.add(go, BorderLayout.EAST);
        panel.add(top, BorderLayout.NORTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    doOKAction();
                }
            }
        });
        JBScrollPane scroll = new JBScrollPane(list);
        scroll.setPreferredSize(new Dimension(420, 260));
        panel.add(scroll, BorderLayout.CENTER);

        panel.add(hint, BorderLayout.SOUTH);
        keyword.addActionListener(e -> doSearch());
        return panel;
    }

    @Nullable
    @Override
    public JComponent getPreferredFocusedComponent() {
        return keyword;
    }

    private void doSearch() {
        final String key = keyword.getText().trim();
        if (key.isEmpty()) {
            return;
        }
        hint.setText("搜索中……");
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                Map<String, String> query = new LinkedHashMap<>();
                query.put("key", key);
                query.put("tab_type", "3");
                query.put("offset", "0");
                Object resp = api.getJson("/api/search", query);
                final List<TomatoMapper.Book> found = TomatoMapper.search(resp);
                ApplicationManager.getApplication().invokeLater(() -> {
                    results = found;
                    model.clear();
                    for (TomatoMapper.Book b : found) {
                        model.addElement(b.toString());
                    }
                    hint.setText(found.isEmpty()
                            ? "没有找到结果"
                            : "共 " + found.size() + " 条，双击条目或选中后点「打开」");
                });
            } catch (final Exception ex) {
                ApplicationManager.getApplication().invokeLater(
                        () -> hint.setText("搜索失败：" + ex.getMessage()));
            }
        });
    }

    @Override
    protected void doOKAction() {
        int i = list.getSelectedIndex();
        if (i < 0 || i >= results.size()) {
            hint.setText("先在列表里选一本书");
            return;
        }
        picked = results.get(i);
        super.doOKAction();
    }

    public String selectedId() {
        return picked == null ? null : picked.id;
    }

    public String selectedName() {
        return picked == null ? "" : picked.name;
    }
}
