package com.tomato.reader.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.tomato.reader.ReaderSettings;
import com.tomato.reader.api.ApiClient;
import com.tomato.reader.api.TomatoMapper;
import com.tomato.reader.infra.Json;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.Dimension;
import java.awt.FlowLayout;

/**
 * 设置页：内容来源、书库目录、接口地址与请求头、显示形态、每行宽度。
 */
public class SettingsConfigurable implements Configurable {

    private static final String[] MODES = {"LOCAL", "ONLINE"};
    private static final String[] SKINS = {"COMMENT", "PROPS", "PLAIN"};

    private JBTextField dirField;
    private JComboBox<String> modeBox;
    private JBTextField baseUrlField;
    private JTextArea headersArea;
    private JComboBox<String> skinBox;
    private JTextField widthField;
    private JTextField autoMsField;
    private JTextField autoLinesField;
    private JButton testBtn;
    private JPanel panel;

    @Override
    public String getDisplayName() {
        return "Work Notes";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        dirField = new JBTextField();
        modeBox = new JComboBox<>(MODES);
        baseUrlField = new JBTextField();
        headersArea = new JTextArea(4, 40);
        headersArea.setLineWrap(true);
        skinBox = new JComboBox<>(SKINS);
        widthField = new JTextField();
        widthField.setPreferredSize(new Dimension(80, widthField.getPreferredSize().height));
        autoMsField = new JTextField();
        autoMsField.setPreferredSize(new Dimension(80, autoMsField.getPreferredSize().height));
        autoLinesField = new JTextField();
        autoLinesField.setPreferredSize(new Dimension(80, autoLinesField.getPreferredSize().height));
        testBtn = new JButton("测试连接");
        testBtn.addActionListener(e -> testConnection());

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        body.add(row("内容来源", modeBox, "LOCAL=本地文件，ONLINE=第三方代理接口"));
        body.add(row("书库目录", dirField, "本地模式下读取 .txt 的目录，留空用默认目录"));

        JPanel headerRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JLabel hl = new JLabel("接口地址");
        hl.setPreferredSize(new Dimension(80, 24));
        headerRow.add(hl);
        headerRow.add(baseUrlField);
        headerRow.add(testBtn);
        body.add(headerRow);

        JPanel headersPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JLabel hhl = new JLabel("请求头");
        hhl.setPreferredSize(new Dimension(80, 24));
        headersPanel.add(hhl);
        JScrollPane hp = new JBScrollPane(headersArea);
        hp.setPreferredSize(new Dimension(420, 80));
        headersPanel.add(hp);
        body.add(headersPanel);

        body.add(hint("每行一个请求头，例如：<br/>X-Phone: 13800000000<br/>Authorization: Bearer xxxxxx"));

        body.add(row("显示形态", skinBox, "COMMENT=伪代码注释，PROPS=i18n 资源包，PLAIN=markdown"));
        body.add(row("每行宽度", widthField, "中文按 2 计，40 约等于 20 个汉字"));

        body.add(row("自动间隔(ms)", autoMsField, "自动阅读时每行停留多久，1200 约等于一秒二滚一行"));
        body.add(row("每次行数", autoLinesField, "每次向下滚几行，建议 1 到 3"));
        body.add(hint("自动阅读用 Ctrl+Alt+Shift+A 开关（也可用面板底部的「自动」勾选框）。"
                + "开启后编辑器自己往下滚，读完一节自动接下一节，不需要点任何按钮；"
                + "当前台标签不是笔记文件时它会自动停住，不会滚动你正在看的其他代码。"));

        body.add(hint("接口路径按 /api/search、/api/detail、/api/book、/api/content 调用，"
                + "地址由你自己提供；本插件不内置任何服务地址。"));

        panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(body);
        return panel;
    }

    private JPanel row(String label, JComponent field, String tip) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JLabel l = new JLabel(label);
        l.setPreferredSize(new Dimension(80, 24));
        p.add(l);
        p.add(field);
        if (tip != null) {
            p.add(new JLabel(tip));
        }
        return p;
    }

    private JPanel hint(String html) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JLabel l = new JLabel("<html><body style='width:520px'>" + html + "</body></html>");
        l.setBorder(BorderFactory.createEmptyBorder(0, 88, 8, 0));
        p.add(l);
        return p;
    }

    private void testConnection() {
        final ApiClient api = new ApiClient(baseUrlField == null ? "" : baseUrlField.getText(),
                ApiClient.parseHeaders(headersArea == null ? "" : headersArea.getText(), null, null), 10000);
        if (!api.isConfigured()) {
            Messages.showInfoMessage(panel, "先填写接口地址。", "Work Notes");
            return;
        }
        testBtn.setEnabled(false);
        testBtn.setText("测试中…");
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            String message;
            try {
                Object resp = api.getJson("/api/auth/status", null);
                Object data = TomatoMapper.digData(resp);
                Object authorized = Json.path(data, "authorized");
                String host = Json.firstString(data, "current_host");
                if (host == null) {
                    host = Json.firstString(data, "authorized_domain");
                }
                if (Boolean.TRUE.equals(authorized)) {
                    message = "连接成功，服务已授权" + (host == null ? "" : "，域名：" + host);
                } else {
                    message = "能连上，但服务返回未授权。原始返回：\n" + brief(data);
                }
            } catch (Exception ex) {
                message = "连接失败：" + ex.getMessage();
            }
            final String text = message;
            ApplicationManager.getApplication().invokeLater(() -> {
                testBtn.setEnabled(true);
                testBtn.setText("测试连接");
                Messages.showInfoMessage(panel, text, "Work Notes");
            });
        });
    }

    private static String brief(Object node) {
        String s = String.valueOf(node);
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    @Override
    public boolean isModified() {
        ReaderSettings s = ReaderSettings.get();
        if (dirField != null && !dirField.getText().trim().equals(nullSafe(s.bookDir))) {
            return true;
        }
        if (modeBox != null && !String.valueOf(modeBox.getSelectedItem()).equals(s.mode)) {
            return true;
        }
        if (baseUrlField != null && !baseUrlField.getText().trim().equals(nullSafe(s.baseUrl))) {
            return true;
        }
        if (headersArea != null && !headersArea.getText().trim().equals(nullSafe(s.extraHeaders))) {
            return true;
        }
        if (skinBox != null && !String.valueOf(skinBox.getSelectedItem()).equals(s.skin)) {
            return true;
        }
        if (widthField != null && parseInt(widthField.getText(), 40) != s.width) {
            return true;
        }
        if (autoMsField != null && parseInt(autoMsField.getText(), 1200) != s.autoIntervalMs) {
            return true;
        }
        return autoLinesField != null && parseInt(autoLinesField.getText(), 1) != s.autoLines;
    }

    @Override
    public void apply() {
        ReaderSettings s = ReaderSettings.get();
        if (dirField != null) {
            s.bookDir = dirField.getText().trim();
        }
        if (modeBox != null) {
            Object v = modeBox.getSelectedItem();
            if (v != null) {
                s.mode = v.toString();
            }
        }
        if (baseUrlField != null) {
            s.baseUrl = baseUrlField.getText().trim();
        }
        if (headersArea != null) {
            s.extraHeaders = headersArea.getText().trim();
        }
        if (skinBox != null) {
            Object v = skinBox.getSelectedItem();
            if (v != null) {
                s.skin = v.toString();
            }
        }
        if (widthField != null) {
            s.width = Math.max(20, Math.min(80, parseInt(widthField.getText(), 40)));
        }
        if (autoMsField != null) {
            s.autoIntervalMs = Math.max(200, Math.min(60000, parseInt(autoMsField.getText(), 1200)));
        }
        if (autoLinesField != null) {
            s.autoLines = Math.max(1, Math.min(20, parseInt(autoLinesField.getText(), 1)));
        }
    }

    @Override
    public void reset() {
        ReaderSettings s = ReaderSettings.get();
        if (dirField != null) {
            dirField.setText(nullSafe(s.bookDir));
        }
        if (modeBox != null) {
            modeBox.setSelectedItem(s.mode == null ? "LOCAL" : s.mode);
        }
        if (baseUrlField != null) {
            baseUrlField.setText(nullSafe(s.baseUrl));
        }
        if (headersArea != null) {
            headersArea.setText(nullSafe(s.extraHeaders));
        }
        if (skinBox != null) {
            skinBox.setSelectedItem(s.skin == null ? "COMMENT" : s.skin);
        }
        if (widthField != null) {
            widthField.setText(String.valueOf(s.width));
        }
        if (autoMsField != null) {
            autoMsField.setText(String.valueOf(s.autoIntervalMs));
        }
        if (autoLinesField != null) {
            autoLinesField.setText(String.valueOf(s.autoLines));
        }
    }

    @Override
    public void disposeUIResources() {
        panel = null;
        dirField = null;
        modeBox = null;
        baseUrlField = null;
        headersArea = null;
        skinBox = null;
        widthField = null;
        autoMsField = null;
        autoLinesField = null;
        testBtn = null;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
