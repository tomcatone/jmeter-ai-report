package com.example.jmeter.aireport;

import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.reporters.AbstractListenerElement;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.engine.util.NoThreadClone;
import org.apache.jmeter.testelement.TestStateListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;

/** 壓測結束時自動生成 HTML / JSON / Markdown(AI 分析用)報告。 */
public class AiReportListener extends AbstractListenerElement
        implements SampleListener, TestStateListener, NoThreadClone, Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(AiReportListener.class);

    public static final String OUTPUT_DIR = "aireport.outputDir";
    public static final String TITLE = "aireport.title";
    public static final String SLA_P95 = "aireport.slaP95Ms";
    public static final String SLA_ERR = "aireport.slaErrorPct";
    public static final String INTERVAL = "aireport.intervalSec";
    public static final String SNIPPET = "aireport.snippetLen";
    public static final String LANG = "aireport.lang";
    public static final String THEME = "aireport.theme";

    private transient volatile StatsCollector collector;

    // ---- 屬性存取
    public String getOutputDir() { return getPropertyAsString(OUTPUT_DIR, "ai-report"); }
    public String getTitle() { return getPropertyAsString(TITLE, "JMeter 壓測報告"); }
    public long getSlaP95() { return getPropertyAsLong(SLA_P95, 0L); }
    public double getSlaErr() { return getPropertyAsString(SLA_ERR, "1").isEmpty() ? 1 : Double.parseDouble(getPropertyAsString(SLA_ERR, "1")); }
    public int getIntervalSec() { return getPropertyAsInt(INTERVAL, 5); }
    public String getLang() { return getPropertyAsString(LANG, "tw"); }
    public String getTheme() { return getPropertyAsString(THEME, "auto"); }
    public int getSnippetLen() { return getPropertyAsInt(SNIPPET, 300); }

    private StatsCollector col() {
        StatsCollector c = collector;
        if (c == null) {
            synchronized (this) {
                if (collector == null) collector = new StatsCollector(getIntervalSec() * 1000L);
                c = collector;
            }
        }
        return c;
    }

    // ---- SampleListener
    @Override
    public void sampleOccurred(SampleEvent e) {
        try {
            SampleResult r = e.getResult();
            StatsCollector c = col();
            boolean tx = r.getSubResults().length > 0
                    || (r.getResponseMessage() != null && r.getResponseMessage().startsWith("Number of samples in transaction"));
            String code = r.getResponseCode();
            String failMsg = "";
            String snippet = "";
            if (!r.isSuccessful()) {
                StringBuilder fm = new StringBuilder();
                for (AssertionResult ar : r.getAssertionResults())
                    if (ar.isFailure() || ar.isError()) fm.append(ar.getName()).append(": ").append(ar.getFailureMessage()).append("; ");
                failMsg = fm.toString();
                if (!tx && !c.errors.containsKey(StatsCollector.errKey(r.getSampleLabel(), code, r.getResponseMessage()))) {
                    String body = r.getResponseDataAsString();
                    int n = getSnippetLen();
                    snippet = body == null ? "" : (body.length() > n ? body.substring(0, n) : body);
                }
            }
            c.add(r.getSampleLabel(), tx, r.getStartTime(), r.getTime(), r.getLatency(), r.getConnectTime(),
                    r.isSuccessful(), code, r.getResponseMessage(), failMsg, snippet,
                    r.getBytesAsLong(), r.getSentBytes(), r.getAllThreads());
        } catch (Exception ex) {
            log.warn("AiReportListener: sample ignored", ex);
        }
    }

    @Override public void sampleStarted(SampleEvent e) { }
    @Override public void sampleStopped(SampleEvent e) { }

    // ---- TestStateListener
    @Override public void testStarted() { testStarted("local"); }
    @Override
    public void testStarted(String host) {
        collector = new StatsCollector(getIntervalSec() * 1000L);
        log.info("AiReportListener started, report dir: {}", Paths.get(getOutputDir()).toAbsolutePath());
    }
    @Override public void testEnded() { testEnded("local"); }

    @Override
    public void testEnded(String host) {
        StatsCollector c = collector;
        if (c == null || c.total.count == 0) {
            log.warn("AiReportListener: no samples, report skipped");
            return;
        }
        try {
            Path dir = Paths.get(getOutputDir(), "report_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()));
            new ReportWriter(c, getTitle(), getSlaP95(), getSlaErr()).style(getLang(), getTheme()).write(dir);
            log.info("AI report generated: {}", dir.toAbsolutePath());
        } catch (Exception ex) {
            log.error("AiReportListener: failed to write report", ex);
        } finally {
            collector = null;
        }
    }
}
