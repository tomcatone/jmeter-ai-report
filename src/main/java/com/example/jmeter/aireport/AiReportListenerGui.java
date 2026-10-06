package com.example.jmeter.aireport;

import org.apache.jmeter.visualizers.gui.AbstractListenerGui;
import org.apache.jmeter.testelement.TestElement;

import javax.swing.*;
import java.awt.*;

public class AiReportListenerGui extends AbstractListenerGui {

    private static final long serialVersionUID = 1L;

    private final JTextField outputDir = new JTextField("ai-report", 30);
    private final JTextField title = new JTextField("JMeter 壓測報告", 30);
    private final JTextField slaP95 = new JTextField("0", 10);
    private final JTextField slaErr = new JTextField("1", 10);
    private final JTextField interval = new JTextField("5", 10);
    private final JTextField snippet = new JTextField("300", 10);
    private static final String[] LANG_CODES = {"tw", "cn"};
    private static final String[] THEME_CODES = {"auto", "light", "dark"};
    private final JComboBox<String> lang = new JComboBox<>(new String[]{"繁體中文", "简体中文"});
    private final JComboBox<String> theme = new JComboBox<>(new String[]{"跟隨系統 / 跟随系统", "白天 / Light", "暗夜 / Dark"});

    public AiReportListenerGui() {
        setLayout(new BorderLayout(0, 5));
        setBorder(makeBorder());
        add(makeTitlePanel(), BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 5, 3, 5);
        g.anchor = GridBagConstraints.WEST;
        String[] names = {"報告標題", "輸出目錄", "SLA: P95 上限(ms,0=不檢查)", "SLA: 錯誤率上限(%)",
                "時序統計間隔(秒)", "錯誤響應片段長度", "報告預設語言", "報告預設主題"};
        JComponent[] fs = {title, outputDir, slaP95, slaErr, interval, snippet, lang, theme};
        for (int i = 0; i < names.length; i++) {
            g.gridx = 0; g.gridy = i;
            form.add(new JLabel(names[i] + ":"), g);
            g.gridx = 1;
            form.add(fs[i], g);
        }
        JPanel north = new JPanel(new BorderLayout());
        north.add(form, BorderLayout.NORTH);
        add(north, BorderLayout.CENTER);
    }

    @Override public String getLabelResource() { return "ai_report_listener"; }
    @Override public String getStaticLabel() { return "AI Report Generator"; }

    @Override
    public TestElement createTestElement() {
        AiReportListener l = new AiReportListener();
        modifyTestElement(l);
        return l;
    }

    @Override
    public void modifyTestElement(TestElement e) {
        super.configureTestElement(e);
        e.setProperty(AiReportListener.TITLE, title.getText());
        e.setProperty(AiReportListener.OUTPUT_DIR, outputDir.getText());
        e.setProperty(AiReportListener.SLA_P95, slaP95.getText());
        e.setProperty(AiReportListener.SLA_ERR, slaErr.getText());
        e.setProperty(AiReportListener.INTERVAL, interval.getText());
        e.setProperty(AiReportListener.SNIPPET, snippet.getText());
        e.setProperty(AiReportListener.LANG, LANG_CODES[Math.max(0, lang.getSelectedIndex())]);
        e.setProperty(AiReportListener.THEME, THEME_CODES[Math.max(0, theme.getSelectedIndex())]);
    }

    @Override
    public void configure(TestElement e) {
        super.configure(e);
        title.setText(e.getPropertyAsString(AiReportListener.TITLE, "JMeter 壓測報告"));
        outputDir.setText(e.getPropertyAsString(AiReportListener.OUTPUT_DIR, "ai-report"));
        slaP95.setText(e.getPropertyAsString(AiReportListener.SLA_P95, "0"));
        slaErr.setText(e.getPropertyAsString(AiReportListener.SLA_ERR, "1"));
        interval.setText(e.getPropertyAsString(AiReportListener.INTERVAL, "5"));
        snippet.setText(e.getPropertyAsString(AiReportListener.SNIPPET, "300"));
        lang.setSelectedIndex(Math.max(0, java.util.Arrays.asList(LANG_CODES).indexOf(e.getPropertyAsString(AiReportListener.LANG, "tw"))));
        theme.setSelectedIndex(Math.max(0, java.util.Arrays.asList(THEME_CODES).indexOf(e.getPropertyAsString(AiReportListener.THEME, "auto"))));
    }

    @Override
    public void clearGui() {
        super.clearGui();
        title.setText("JMeter 壓測報告");
        outputDir.setText("ai-report");
        slaP95.setText("0");
        slaErr.setText("1");
        interval.setText("5");
        snippet.setText("300");
        lang.setSelectedIndex(0);
        theme.setSelectedIndex(0);
    }
}
