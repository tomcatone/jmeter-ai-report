package com.example.jmeter.aireport;

import com.example.jmeter.aireport.StatsCollector.Agg;
import com.example.jmeter.aireport.StatsCollector.Err;
import com.example.jmeter.aireport.StatsCollector.Slice;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * 產出 4 類檔案:
 *  report.html          人看的報告(概覽/明細/錯誤/趨勢圖,無外部依賴)
 *  summary.json         結構化完整數據
 *  ai_analysis_data.md  精簡 + 內建分析提示詞,直接貼給 AI
 *  labels.csv/errors.csv 方便二次處理
 */
public class ReportWriter {

    private static final String[] HEAD = {"接口/事務", "樣本數", "失敗數", "錯誤率%", "平均(ms)", "最小(ms)",
            "最大(ms)", "P50", "P90", "P95", "P99", "TPS", "接收KB/s", "平均Latency", "平均Connect"};
    private static final String[] KEYS = {"label", "samples", "failures", "errorPct", "avgMs", "minMs",
            "maxMs", "p50", "p90", "p95", "p99", "tps", "recvKBps", "avgLatencyMs", "avgConnectMs"};

    private final StatsCollector c;
    private final String title;
    private final long slaP95;
    private final double slaErrPct;

    public ReportWriter(StatsCollector c, String title, long slaP95, double slaErrPct) {
        this.c = c;
        this.title = title;
        this.slaP95 = slaP95;
        this.slaErrPct = slaErrPct;
    }

    public void write(Path dir) throws IOException {
        Files.createDirectories(dir);
        put(dir, "report.html", html());
        put(dir, "summary.json", json());
        put(dir, "ai_analysis_data.md", markdown());
        put(dir, "labels.csv", labelsCsv());
        put(dir, "errors.csv", errorsCsv());
    }

    // ------------------------------------------------------------ helpers

    private static void put(Path dir, String name, String s) throws IOException {
        Files.write(dir.resolve(name), s.getBytes(StandardCharsets.UTF_8));
    }

    private static String f(double d) { return String.format(Locale.ROOT, "%.2f", d); }
    private static String ts(long t) {
        return t <= 0 || t == Long.MAX_VALUE ? "-" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(t));
    }

    private static String[] cells(Agg a) {
        return new String[]{a.label, "" + a.count, "" + a.errors, f(a.errPct()), f(a.avg()), "" + a.min(),
                "" + a.maxElapsed, "" + a.hist.percentile(50), "" + a.hist.percentile(90),
                "" + a.hist.percentile(95), "" + a.hist.percentile(99), f(a.tps()), f(a.recvKBps()),
                f(a.avgLatency()), f(a.avgConnect())};
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String js(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': break;
                case '\t': b.append("\\t"); break;
                case '<': b.append("\\u003c"); break;
                default: if (ch < 0x20) b.append(' '); else b.append(ch);
            }
        }
        return b.append('"').toString();
    }

    private static String csv(String s) {
        return "\"" + (s == null ? "" : s.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ")) + "\"";
    }

    private List<Agg> sorted(Map<String, Agg> m) {
        List<Agg> l = new ArrayList<>(m.values());
        l.sort(Comparator.comparing(a -> a.label));
        return l;
    }

    private List<Err> sortedErrors() {
        List<Err> l = new ArrayList<>(c.errors.values());
        l.sort((x, y) -> Long.compare(y.count.sum(), x.count.sum()));
        return l;
    }

    private List<Agg> sortedGroups() {
        return sorted(c.groups);
    }

    private List<Agg> sortedGroupSamplers(String g) {
        Map<String, Agg> m = c.groupSamplers.get(g);
        return m == null ? new ArrayList<>() : sorted(m);
    }

    // ------------------------------------------------- 趨勢 / 分佈 數據

    private static final long[] EDGES = {10, 25, 50, 100, 200, 300, 500, 800, 1000, 1500, 2000, 3000, 5000, 10000};

    private static String[] bucketNames() {
        String[] n = new String[EDGES.length + 1];
        n[0] = "<" + EDGES[0];
        for (int i = 1; i < EDGES.length; i++) n[i] = EDGES[i - 1] + "-" + EDGES[i];
        n[EDGES.length] = "≥" + EDGES[EDGES.length - 1];
        return n;
    }

    private static long[] dist(Agg a) {
        long[] r = new long[EDGES.length + 1];
        long prev = 0;
        for (int i = 0; i < EDGES.length; i++) {
            long cum = a.hist.countLessThan(EDGES[i]);
            r[i] = cum - prev;
            prev = cum;
        }
        r[EDGES.length] = a.hist.total() - prev;
        return r;
    }

    private static String longs(long[] a) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) b.append(i > 0 ? "," : "").append(a[i]);
        return b.append("]").toString();
    }

    private List<Agg> byCount(Map<String, Agg> m, int limit) {
        List<Agg> l = new ArrayList<>(m.values());
        l.sort((x, y) -> Long.compare(y.count, x.count));
        return l.size() > limit ? l.subList(0, limit) : l;
    }

    /** {"labels":[],"all":[],"groups":[{"n":,"c":[]}],"samplers":[{"n":,"c":[]}]} */
    private String distJson() {
        StringBuilder b = new StringBuilder("{\"labels\":[");
        String[] nm = bucketNames();
        for (int i = 0; i < nm.length; i++) b.append(i > 0 ? "," : "").append(js(nm[i]));
        b.append("],\"unit\":\"ms\",\"all\":").append(longs(dist(c.total))).append(",\"groups\":[");
        boolean first = true;
        for (Agg a : byCount(c.groups, 100)) {
            b.append(first ? "" : ",").append("{\"n\":").append(js(a.label)).append(",\"c\":").append(longs(dist(a))).append("}");
            first = false;
        }
        b.append("],\"samplers\":[");
        first = true;
        for (Agg a : byCount(c.samplers, 200)) {
            b.append(first ? "" : ",").append("{\"n\":").append(js(a.label)).append(",\"c\":").append(longs(dist(a))).append("}");
            first = false;
        }
        return b.append("]}").toString();
    }

    private static final class Trend {
        double[] t = new double[0];
        final List<String> names = new ArrayList<>();
        final List<double[]> tps = new ArrayList<>(), avg = new ArrayList<>(), max = new ArrayList<>(), err = new ArrayList<>();
    }

    private List<String> orderBy(Map<String, Agg> aggs, Map<String, ?> series) {
        List<String> out = new ArrayList<>();
        for (Agg a : byCount(aggs, Integer.MAX_VALUE)) if (series.containsKey(a.label)) out.add(a.label);
        return out;
    }

    private Trend trend(Map<String, ConcurrentSkipListMap<Long, Slice>> m, List<String> order, int maxPts) {
        Trend tr = new Trend();
        if (c.series.isEmpty()) return tr;
        long t0 = c.series.firstKey(), iv = c.intervalMs;
        int n = (int) ((c.series.lastKey() - t0) / iv) + 1;
        int stride = Math.max(1, (int) Math.ceil(n / (double) maxPts));
        int nb = (n + stride - 1) / stride;
        tr.t = new double[nb];
        for (int k = 0; k < nb; k++) tr.t[k] = (long) k * stride * iv / 1000.0;
        for (String name : order) {
            ConcurrentSkipListMap<Long, Slice> s = m.get(name);
            if (s == null) continue;
            double[] tps = new double[nb], avg = new double[nb], mx = new double[nb], er = new double[nb];
            for (int k = 0; k < nb; k++) {
                long cnt = 0, err = 0, sum = 0, mxe = 0;
                int span = 0;
                for (int j = k * stride; j < Math.min(n, (k + 1) * stride); j++, span++) {
                    Slice sl = s.get(t0 + j * iv);
                    if (sl == null) continue;
                    cnt += sl.count; err += sl.errors; sum += sl.sumElapsed; mxe = Math.max(mxe, sl.maxElapsed);
                }
                tps[k] = cnt / (span * iv / 1000.0);
                avg[k] = cnt == 0 ? Double.NaN : (double) sum / cnt;
                mx[k] = cnt == 0 ? Double.NaN : mxe;
                er[k] = cnt == 0 ? Double.NaN : err * 100.0 / cnt;
            }
            tr.names.add(name);
            tr.tps.add(tps); tr.avg.add(avg); tr.max.add(mx); tr.err.add(er);
        }
        return tr;
    }

    private static void arr(StringBuilder b, double[] a) {
        b.append("[");
        for (int i = 0; i < a.length; i++) b.append(i > 0 ? "," : "").append(Double.isNaN(a[i]) ? "null" : f(a[i]));
        b.append("]");
    }

    private static void nested(StringBuilder b, String key, List<double[]> l) {
        b.append(",\"").append(key).append("\":[");
        for (int i = 0; i < l.size(); i++) { if (i > 0) b.append(","); arr(b, l.get(i)); }
        b.append("]");
    }

    /** {"t":[相對秒],"names":[],"tps":[[..]],"avg":[[..]],"max":[[..]],"err":[[..]]};null=該區間無樣本 */
    private String trendJson(Trend tr) {
        StringBuilder b = new StringBuilder("{\"t\":");
        arr(b, tr.t);
        b.append(",\"names\":[");
        for (int i = 0; i < tr.names.size(); i++) b.append(i > 0 ? "," : "").append(js(tr.names.get(i)));
        b.append("]");
        nested(b, "tps", tr.tps); nested(b, "avg", tr.avg); nested(b, "max", tr.max); nested(b, "err", tr.err);
        return b.append("}").toString();
    }

    private Trend groupTrend(int maxPts) { return trend(c.groupSeries, orderBy(c.groups, c.groupSeries), maxPts); }
    private Trend labelTrend(int maxPts) { return trend(c.labelSeries, orderBy(c.samplers, c.labelSeries), maxPts); }

    private static String res(String name) {
        try (InputStream in = ReportWriter.class.getResourceAsStream(name)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** 注入 HTML 的額外腳本:趨勢/分佈數據 + charts.js。 */
    private String extraScript() {
        StringBuilder b = new StringBuilder();
        if (c.groups.size() > 1) b.append("var TRG=").append(trendJson(groupTrend(150))).append(";");
        if (c.samplers.size() > 1) b.append("var TRS=").append(trendJson(labelTrend(150))).append(";");
        b.append("var DIST=").append(distJson()).append(";");
        b.append(res("charts.js"));
        b.append("\ntry{initExtra()}catch(e){if(window.console)console.error(e)}");
        return b.toString();
    }

    private boolean verdict() {
        boolean ok = c.total.errPct() <= slaErrPct;
        if (slaP95 > 0 && c.total.hist.percentile(95) > slaP95) ok = false;
        return ok;
    }

    private boolean slaBreach(Agg a) {
        return slaP95 > 0 && a.hist.percentile(95) > slaP95;
    }

    private String slaText() {
        return "錯誤率 ≤ " + slaErrPct + "%" + (slaP95 > 0 ? ",P95 ≤ " + slaP95 + "ms" : "");
    }

    /** 時序點:[相對秒, TPS, 平均RT, 錯誤率%, 線程數, 最大RT],最多 maxPts 個。 */
    private List<double[]> points(int maxPts) {
        List<Map.Entry<Long, Slice>> es = new ArrayList<>(c.series.entrySet());
        List<double[]> out = new ArrayList<>();
        if (es.isEmpty()) return out;
        int stride = Math.max(1, (int) Math.ceil(es.size() / (double) maxPts));
        long t0 = es.get(0).getKey();
        for (int i = 0; i < es.size(); i += stride) {
            long cnt = 0, err = 0, sum = 0, mx = 0;
            int th = 0, n = 0;
            for (int j = i; j < Math.min(es.size(), i + stride); j++, n++) {
                Slice s = es.get(j).getValue();
                cnt += s.count; err += s.errors; sum += s.sumElapsed;
                mx = Math.max(mx, s.maxElapsed); th = Math.max(th, s.threads);
            }
            double span = n * c.intervalMs / 1000.0;
            out.add(new double[]{(es.get(i).getKey() - t0) / 1000.0, cnt / span,
                    cnt == 0 ? 0 : (double) sum / cnt, cnt == 0 ? 0 : err * 100.0 / cnt, th, mx});
        }
        return out;
    }

    // --------------------------------------------------------------- HTML

    // ---------------------------------------------------------- i18n (繁/简)

    private String lang = "tw";     // tw | cn
    private String theme = "auto";  // auto | light | dark

    public ReportWriter style(String lang, String theme) {
        if ("cn".equals(lang) || "tw".equals(lang)) this.lang = lang;
        if ("auto".equals(theme) || "light".equals(theme) || "dark".equals(theme)) this.theme = theme;
        return this;
    }

    private static final String[] PAIRS = {
            "結结", "覽览", "趨趋", "勢势", "圖图", "細细", "務务", "錯错", "誤误", "與与", "異异", "響响",
            "應应", "碼码", "佈布", "總总", "請请", "數数", "樣样", "個个", "敗败", "時时", "間间", "並并", "發发", "線线",
            "躍跃", "沒没", "佔占", "類类", "斷断", "訊讯", "現现", "據据", "傳传", "給给", "連连", "層层", "戶户", "標标",
            "訖讫", "測测", "試试", "壓压", "報报", "觀观", "歷历", "動动", "點点", "輸输", "區区", "擊击", "組组", "場场", "線线"};

    private static String toCn(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            char out = ch;
            for (String pr : PAIRS) if (pr.length() >= 2 && pr.charAt(0) == ch) { out = pr.charAt(1); break; }
            b.append(out);
        }
        return b.toString();
    }

    /** 靜態文字:以繁體書寫,輸出成可在瀏覽器切換簡繁的 span。 */
    private static String L(String tw) {
        String cn = toCn(tw);
        if (cn.equals(tw)) return esc(tw);
        return "<span class='i' data-cn=\"" + esc(cn).replace("\"", "&quot;") + "\">" + esc(tw) + "</span>";
    }

    // --------------------------------------------------------------- HTML

    private String table(List<Agg> rows, boolean flag) {
        return table(rows, flag, HEAD[0], false);
    }

    private String table(List<Agg> rows, boolean flag, String firstHead, boolean grp) {
        StringBuilder b = new StringBuilder("<table><tr>");
        for (int i = 0; i < HEAD.length; i++) b.append("<th>").append(L(i == 0 ? firstHead : HEAD[i])).append("</th>");
        if (grp) b.append("<th>").append(L("最大線程數")).append("</th><th>").append(L("接口數")).append("</th>");
        if (flag) b.append("<th>SLA</th>");
        b.append("</tr>");
        for (Agg a : rows) {
            String[] cs = cells(a);
            b.append(a.errors > 0 ? "<tr class='bad'>" : "<tr>");
            for (int i = 0; i < cs.length; i++) b.append(i == 0 ? "<td class='l'>" : "<td>").append(esc(cs[i])).append("</td>");
            if (grp) {
                Map<String, Agg> m = c.groupSamplers.get(a.label);
                b.append("<td>").append(c.groupThreads.getOrDefault(a.label, 0)).append("</td><td>")
                        .append(m == null ? 0 : m.size()).append("</td>");
            }
            if (flag) b.append("<td>").append(slaBreach(a) ? L("⚠ 超標") : "✔").append("</td>");
            b.append("</tr>");
        }
        return b.append("</table>").toString();
    }

    private String html() {
        Agg t = c.total;
        boolean pass = verdict();
        long wall = c.maxEndTs.get() - c.minTs.get();
        StringBuilder b = new StringBuilder();
        b.append("<!DOCTYPE html><html lang='").append("cn".equals(lang) ? "zh-CN" : "zh-TW")
                .append("'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>")
                .append(esc(title)).append("</title><style>")
                .append(":root{--bg:#fafafa;--fg:#222;--card:#fff;--bd:#e0e0e0;--th:#f0f0f0;--bad:#fff4f4;--pre:#f4f4f4;--mut:#777;--pass:#1a8f3c;--fail:#d32f2f;--grid:#ccc}")
                .append("html[data-theme=dark]{--bg:#12151c;--fg:#e4e6eb;--card:#1c212b;--bd:#323946;--th:#262d3a;--bad:#3a1f24;--pre:#161a22;--mut:#9aa3b2;--pass:#4cd07d;--fail:#ff6b6b;--grid:#444c5c}")
                .append("body{font-family:-apple-system,'Segoe UI','Microsoft JhengHei','Microsoft YaHei',sans-serif;margin:24px;color:var(--fg);background:var(--bg);transition:background .2s,color .2s}")
                .append("h1{margin:0 0 4px}h2{margin-top:32px;border-bottom:2px solid var(--bd);padding-bottom:4px}")
                .append(".tb{position:fixed;top:10px;right:14px;display:flex;gap:4px;z-index:9;background:var(--card);border:1px solid var(--bd);border-radius:8px;padding:4px}")
                .append(".tb button{border:0;background:transparent;color:var(--fg);padding:4px 9px;border-radius:5px;cursor:pointer;font-size:13px}")
                .append(".tb button.on{background:#1976d2;color:#fff}.tb i{width:1px;background:var(--bd);margin:2px 2px}")
                .append(".cards{display:flex;flex-wrap:wrap;gap:12px}.card{background:var(--card);border:1px solid var(--bd);border-radius:8px;padding:12px 18px;min-width:130px}")
                .append(".card b{display:block;font-size:22px}.card span.s{color:var(--mut);font-size:12px}")
                .append(".pass{color:var(--pass)}.fail{color:var(--fail)}")
                .append("table{border-collapse:collapse;background:var(--card);font-size:13px;width:100%}th,td{border:1px solid var(--bd);padding:5px 8px;text-align:right}")
                .append("th{background:var(--th)}td.l{text-align:left;word-break:break-all}tr.bad td{background:var(--bad)}")
                .append(".wrap{overflow-x:auto}.mt{display:flex;gap:4px;margin:6px 0}.mt button{border:1px solid var(--bd);background:var(--card);color:var(--fg);padding:4px 10px;border-radius:5px;cursor:pointer;font-size:12px}.mt button.on{background:#1976d2;color:#fff;border-color:#1976d2}.cw{position:relative}.tip{position:absolute;top:8px;display:none;background:var(--card);border:1px solid var(--bd);padding:6px 8px;font-size:12px;border-radius:6px;pointer-events:none;white-space:nowrap;z-index:5}.lg{display:flex;flex-wrap:wrap;gap:4px 14px;margin:6px 0;font-size:12px}.lg label{cursor:pointer;display:flex;align-items:center;gap:4px}.lg i{width:12px;height:12px;display:inline-block;border-radius:2px}h3{margin:22px 0 6px}.mc select{padding:3px 6px;background:var(--card);color:var(--fg);border:1px solid var(--bd);border-radius:5px;margin:6px 0}details{margin:10px 0}summary{cursor:pointer;padding:4px 0}.charts{display:grid;grid-template-columns:repeat(auto-fit,minmax(480px,1fr));gap:12px}")
                .append("canvas{background:var(--card);border:1px solid var(--bd);width:100%}pre{white-space:pre-wrap;background:var(--pre);padding:6px;font-size:12px}")
                .append("</style></head><body>");

        b.append("<div class='tb'>")
                .append("<button data-k='theme' data-v='auto' title='Auto'>🖥</button>")
                .append("<button data-k='theme' data-v='light' title='Light'>☀️</button>")
                .append("<button data-k='theme' data-v='dark' title='Dark'>🌙</button><i></i>")
                .append("<button data-k='lang' data-v='tw'>繁</button>")
                .append("<button data-k='lang' data-v='cn'>简</button></div>");

        b.append("<h1>").append(L(title)).append("</h1><div>").append(ts(c.minTs.get())).append(" ~ ")
                .append(ts(c.maxEndTs.get())).append("(").append(wall / 1000).append(" ").append(L("秒")).append(")</div>");

        b.append("<h2>1. ").append(L("結果概覽")).append("</h2><div class='cards'>");
        card(b, "判定", pass ? "<span class='pass'>PASS</span>" : "<span class='fail'>FAIL</span>", slaText());
        card(b, "總請求數", "" + t.count, "不含事務樣本");
        card(b, "錯誤率", f(t.errPct()) + "%", t.errors + " 個失敗");
        card(b, "平均吞吐 TPS", f(t.tps()), "請求/秒");
        card(b, "平均響應", f(t.avg()) + " ms", "min " + t.min() + " / max " + t.maxElapsed);
        card(b, "P90 / P95 / P99", t.hist.percentile(90) + " / " + t.hist.percentile(95) + " / " + t.hist.percentile(99), "ms");
        card(b, "最大並發線程", "" + c.maxThreads, "");
        card(b, "接收/發送", f(t.recvKBps()) + " / " + f(t.sentKBps()), "KB/s");
        b.append("</div>");

        b.append("<h2>2. ").append(L("趨勢圖")).append("</h2><div class='charts'>")
                .append("<div>TPS<canvas id='c1' width='560' height='220'></canvas></div>")
                .append("<div>").append(L("平均響應時間")).append(" (ms)<canvas id='c2' width='560' height='220'></canvas></div>")
                .append("<div>").append(L("錯誤率")).append(" (%)<canvas id='c3' width='560' height='220'></canvas></div>")
                .append("<div>").append(L("活躍線程數")).append("<canvas id='c4' width='560' height='220'></canvas></div></div>");
        if (c.groups.size() > 1)
            b.append("<h3>").append(L("場景趨勢")).append("</h3><div id='mcG' class='mc'></div>");
        if (c.samplers.size() > 1)
            b.append("<h3>").append(L("接口趨勢")).append("</h3><div id='mcS' class='mc'></div>");
        b.append("<h3>").append(L("響應時間分佈")).append("</h3><div id='hist' class='mc'></div>");

        b.append("<h2>3. ").append(L("線程組/場景")).append("</h2><div class='wrap'>")
                .append(table(sortedGroups(), true, "線程組/場景", true)).append("</div>");
        if (c.groups.size() > 1)
            for (Agg g : sortedGroups())
                b.append("<details><summary><b>").append(esc(g.label)).append("</b> — ").append(g.count).append(" ")
                        .append(L("樣本")).append(" / ").append(f(g.errPct())).append("% ").append(L("錯誤率"))
                        .append("</summary><div class='wrap'>").append(table(sortedGroupSamplers(g.label), true))
                        .append("</div></details>");

        b.append("<h2>4. ").append(L("接口明細")).append("</h2><div class='wrap'>").append(table(sorted(c.samplers), true)).append("</div>");
        if (!c.transactions.isEmpty())
            b.append("<h2>5. ").append(L("事務明細")).append("</h2><div class='wrap'>").append(table(sorted(c.transactions), true)).append("</div>");

        b.append("<h2>6. ").append(L("錯誤與異常")).append("</h2>");
        List<Err> es = sortedErrors();
        if (es.isEmpty()) b.append("<p class='pass'>").append(L("沒有錯誤")).append(" 🎉</p>");
        else {
            b.append("<div class='wrap'><table><tr>");
            for (String h : new String[]{"次數", "佔失敗%", "分類", "線程組", "接口", "響應碼", "響應訊息", "斷言/失敗訊息", "首次出現"})
                b.append("<th>").append(L(h)).append("</th>");
            b.append("</tr>");
            for (Err e : es) {
                b.append("<tr><td>").append(e.count.sum()).append("</td><td>")
                        .append(f(t.errors == 0 ? 0 : e.count.sum() * 100.0 / t.errors)).append("</td><td class='l'>")
                        .append(L(e.category())).append("</td><td class='l'>").append(esc(e.group)).append("</td><td class='l'>").append(esc(e.label)).append("</td><td class='l'>")
                        .append(esc(e.code)).append("</td><td class='l'>").append(esc(e.message)).append("</td><td class='l'>")
                        .append(esc(e.failureMessage));
                if (!e.snippet.isEmpty()) b.append("<pre>").append(esc(e.snippet)).append("</pre>");
                b.append("</td><td>").append(ts(e.firstTs)).append("</td></tr>");
            }
            b.append("</table></div>");
        }

        b.append("<h2>7. ").append(L("響應碼分佈")).append("</h2><div class='wrap'><table><tr><th>")
                .append(L("響應碼")).append("</th><th>").append(L("次數")).append("</th></tr>");
        c.codes.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue().sum(), x.getValue().sum()))
                .forEach(e -> b.append("<tr><td class='l'>").append(esc(e.getKey())).append("</td><td>")
                        .append(e.getValue().sum()).append("</td></tr>"));
        b.append("</table></div>");
        b.append("<p style='color:var(--mut);margin-top:30px'>").append(L("附:ai_analysis_data.md 可直接上傳給 AI 做分析。")).append("</p>");

        List<double[]> pts = points(300);
        b.append("<script>var D=").append(seriesJs(pts)).append(";")
                .append("var H=document.documentElement,theme='").append(theme).append("',lang='").append(lang).append("';")
                .append("function ld(k,d){try{return localStorage.getItem(k)||d}catch(e){return d}}")
                .append("function sv(k,v){try{localStorage.setItem(k,v)}catch(e){}}")
                .append("theme=ld('air.theme',theme);lang=ld('air.lang',lang);")
                .append("function css(n){return getComputedStyle(H).getPropertyValue(n)}")
                .append("function draw(id,ys,col){var c=document.getElementById(id),g=c.getContext('2d'),w=c.width,h=c.height,p=40;")
                .append("g.clearRect(0,0,w,h);if(!ys.length)return;var m=Math.max.apply(null,ys.concat([0.0001]));")
                .append("g.strokeStyle=css('--grid');g.beginPath();g.moveTo(p,5);g.lineTo(p,h-25);g.lineTo(w-5,h-25);g.stroke();")
                .append("g.fillStyle=css('--mut');g.font='11px sans-serif';g.fillText(m.toFixed(1),2,14);g.fillText(D.t[D.t.length-1]+'s',w-40,h-8);")
                .append("g.strokeStyle=col;g.lineWidth=1.5;g.beginPath();ys.forEach(function(y,i){var x=p+(w-p-5)*i/Math.max(ys.length-1,1),")
                .append("yy=h-25-(h-30)*y/m;i?g.lineTo(x,yy):g.moveTo(x,yy)});g.stroke();}")
                .append("function drawAll(){draw('c1',D.tps,'#1976d2');draw('c2',D.avg,'#f57c00');draw('c3',D.err,'#e53935');draw('c4',D.th,'#43a047');if(window.REDRAW)REDRAW.forEach(function(f){f()})}")
                .append("function mark(){document.querySelectorAll('.tb button').forEach(function(b){")
                .append("b.className=(b.dataset.k=='theme'?theme:lang)==b.dataset.v?'on':''})}")
                .append("function applyTheme(){var t=theme=='auto'?(matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light'):theme;")
                .append("H.setAttribute('data-theme',t);mark();drawAll();}")
                .append("function applyLang(){document.querySelectorAll('.i,option[data-cn]').forEach(function(e){if(!e.hasAttribute('data-tw'))e.setAttribute('data-tw',e.textContent);")
                .append("e.textContent=lang=='cn'?e.getAttribute('data-cn'):e.getAttribute('data-tw')});H.lang=lang=='cn'?'zh-CN':'zh-TW';mark();}")
                .append("document.querySelectorAll('.tb button').forEach(function(b){b.onclick=function(){")
                .append("if(b.dataset.k=='theme'){theme=b.dataset.v;sv('air.theme',theme);applyTheme()}else{lang=b.dataset.v;sv('air.lang',lang);applyLang()}}});")
                .append("try{matchMedia('(prefers-color-scheme: dark)').addEventListener('change',function(){if(theme=='auto')applyTheme()})}catch(e){}")
                .append(extraScript()).append("\napplyLang();applyTheme();</script>")
                .append("</body></html>");
        return b.toString();
    }

    private static void card(StringBuilder b, String name, String val, String sub) {
        b.append("<div class='card'><span class='s'>").append(L(name)).append("</span><b>").append(val)
                .append("</b><span class='s'>").append(L(sub)).append("</span></div>");
    }

    private String seriesJs(List<double[]> pts) {
        String[] n = {"t", "tps", "avg", "err", "th", "max"};
        int[] col = {0, 1, 2, 3, 4, 5};
        StringBuilder b = new StringBuilder("{");
        for (int k = 0; k < n.length; k++) {
            b.append(k > 0 ? "," : "").append(n[k]).append(":[");
            for (int i = 0; i < pts.size(); i++)
                b.append(i > 0 ? "," : "").append(f(pts.get(i)[col[k]]));
            b.append("]");
        }
        return b.append("}").toString();
    }

    // --------------------------------------------------------------- JSON

    private void aggJson(StringBuilder b, Agg a) {
        String[] cs = cells(a);
        b.append("{");
        for (int i = 0; i < cs.length; i++) {
            b.append(i > 0 ? "," : "").append(js(KEYS[i])).append(":");
            if (i == 0) b.append(js(cs[i])); else b.append(cs[i]);
        }
        b.append(",\"slaP95Breached\":").append(slaBreach(a)).append("}");
    }

    private String json() {
        Agg t = c.total;
        StringBuilder b = new StringBuilder("{\n");
        b.append("\"title\":").append(js(title)).append(",\n\"verdict\":").append(js(verdict() ? "PASS" : "FAIL"))
                .append(",\n\"sla\":{\"maxErrorPct\":").append(slaErrPct).append(",\"maxP95Ms\":").append(slaP95).append("},\n")
                .append("\"start\":").append(js(ts(c.minTs.get()))).append(",\"end\":").append(js(ts(c.maxEndTs.get())))
                .append(",\"durationSec\":").append((c.maxEndTs.get() - c.minTs.get()) / 1000).append(",\n")
                .append("\"maxThreads\":").append(c.maxThreads).append(",\n\"overall\":");
        aggJson(b, t);
        b.append(",\n\"samplers\":[");
        join(b, sorted(c.samplers));
        b.append("],\n\"threadGroups\":[");
        boolean fg = true;
        for (Agg g : sortedGroups()) {
            if (!fg) b.append(",");
            fg = false;
            b.append("{\"name\":").append(js(g.label)).append(",\"maxThreads\":")
                    .append(c.groupThreads.getOrDefault(g.label, 0)).append(",\"summary\":");
            aggJson(b, g);
            b.append(",\"samplers\":[");
            join(b, sortedGroupSamplers(g.label));
            b.append("]}");
        }
        b.append("],\n\"transactions\":[");
        join(b, sorted(c.transactions));
        b.append("],\n\"errors\":[");
        boolean first = true;
        for (Err e : sortedErrors()) {
            if (!first) b.append(",");
            first = false;
            b.append("{\"count\":").append(e.count.sum()).append(",\"category\":").append(js(e.category()))
                    .append(",\"threadGroup\":").append(js(e.group)).append(",\"label\":").append(js(e.label)).append(",\"code\":").append(js(e.code))
                    .append(",\"message\":").append(js(e.message)).append(",\"failureMessage\":").append(js(e.failureMessage))
                    .append(",\"sampleResponse\":").append(js(e.snippet)).append(",\"firstSeen\":").append(js(ts(e.firstTs))).append("}");
        }
        b.append("],\n\"responseCodes\":{");
        first = true;
        for (Map.Entry<String, java.util.concurrent.atomic.LongAdder> e : c.codes.entrySet()) {
            if (!first) b.append(",");
            first = false;
            b.append(js(e.getKey())).append(":").append(e.getValue().sum());
        }
        b.append("},\n\"responseTimeDistribution\":").append(distJson()).append(",\n\"trends\":{\"note\":\"null = no samples in that interval; t = seconds since first interval\",\"threadGroups\":")
                .append(trendJson(groupTrend(60))).append(",\"samplers\":").append(trendJson(labelTrend(60)))
                .append("},\n\"timeSeries\":{\"intervalNote\":\"downsampled to <=120 points\",\"points\":[");
        List<double[]> pts = points(120);
        for (int i = 0; i < pts.size(); i++) {
            double[] p = pts.get(i);
            b.append(i > 0 ? "," : "").append("{\"t\":").append(f(p[0])).append(",\"tps\":").append(f(p[1]))
                    .append(",\"avgMs\":").append(f(p[2])).append(",\"errPct\":").append(f(p[3]))
                    .append(",\"threads\":").append((int) p[4]).append(",\"maxMs\":").append((long) p[5]).append("}");
        }
        return b.append("]}\n}\n").toString();
    }

    private void join(StringBuilder b, List<Agg> l) {
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) b.append(",");
            aggJson(b, l.get(i));
        }
    }

    // ----------------------------------------------------------- Markdown

    private String mdTable(List<Agg> rows) {
        return mdTable(rows, false);
    }

    private String mdTable(List<Agg> rows, boolean grp) {
        StringBuilder b = new StringBuilder("|");
        for (int i = 0; i < HEAD.length; i++) b.append(grp && i == 0 ? "線程組/場景" : HEAD[i]).append("|");
        if (grp) b.append("最大線程數|接口數|");
        b.append("\n|");
        for (int i = 0; i < HEAD.length + (grp ? 2 : 0); i++) b.append("---|");
        b.append("\n");
        for (Agg a : rows) {
            b.append("|");
            for (String s : cells(a)) b.append(s.replace("|", "\\|")).append("|");
            if (grp) {
                Map<String, Agg> m = c.groupSamplers.get(a.label);
                b.append(c.groupThreads.getOrDefault(a.label, 0)).append("|").append(m == null ? 0 : m.size()).append("|");
            }
            b.append(slaBreach(a) ? " ⚠P95超標" : "").append("\n");
        }
        return b.toString();
    }

    private String markdown() {
        Agg t = c.total;
        StringBuilder b = new StringBuilder();
        b.append("# 壓測結果分析資料:").append(title).append("\n\n");
        b.append("## 給 AI 的任務說明\n")
                .append("你是資深性能測試工程師。請依據下方 JMeter 壓測數據(響應時間單位 ms)完成:\n")
                .append("1. 判斷本次壓測是否達標,並給出依據(對照 SLA)。\n")
                .append("2. 找出性能瓶頸接口,按嚴重程度排序,說明判斷依據(P95/P99 與平均值差距、TPS、錯誤率)。若有多個線程組/場景,請比較各場景差異並指出拖累整體指標的場景。\n")
                .append("3. 分析時序趨勢:隨線程數上升,TPS 是否出現拐點/下降、響應時間是否劣化、錯誤何時開始出現。\n")
                .append("4. 歸類錯誤原因(超時/連線被拒/5xx/斷言失敗等),推測根因。\n")
                .append("5. 給出具體優化建議與下一輪壓測方案(並發梯度、持續時間、需補充的監控指標如 CPU/記憶體/GC/DB/連線池)。\n")
                .append("注意:本數據只含客戶端視角,若缺少服務端資源指標,請明確指出哪些結論需要進一步驗證。\n")
                .append("百分位數來自近似直方圖(<2s 精度 1ms,<20s 精度 10ms)。事務樣本單獨列出,未計入 TOTAL。\n\n");

        b.append("## 1. 測試概覽\n")
                .append("- 時間:").append(ts(c.minTs.get())).append(" ~ ").append(ts(c.maxEndTs.get()))
                .append("(").append((c.maxEndTs.get() - c.minTs.get()) / 1000).append(" 秒)\n")
                .append("- SLA:").append(slaText()).append(" → 判定 **").append(verdict() ? "PASS" : "FAIL").append("**\n")
                .append("- 總請求:").append(t.count).append(",失敗:").append(t.errors).append("(").append(f(t.errPct())).append("%)\n")
                .append("- 平均 TPS:").append(f(t.tps())).append(",最大並發線程:").append(c.maxThreads).append("\n")
                .append("- 響應時間 avg/min/max:").append(f(t.avg())).append("/").append(t.min()).append("/").append(t.maxElapsed)
                .append(",P50/P90/P95/P99:").append(t.hist.percentile(50)).append("/").append(t.hist.percentile(90)).append("/")
                .append(t.hist.percentile(95)).append("/").append(t.hist.percentile(99)).append("\n")
                .append("- 吞吐:接收 ").append(f(t.recvKBps())).append(" KB/s,發送 ").append(f(t.sentKBps())).append(" KB/s\n\n");

        b.append("## 2. 線程組/場景(各場景整體表現,用於比較場景間差異)\n").append(mdTable(sortedGroups(), true)).append("\n");
        if (c.groups.size() > 1)
            for (Agg g : sortedGroups())
                b.append("### 場景:").append(g.label).append("\n").append(mdTable(sortedGroupSamplers(g.label))).append("\n");
        b.append("## 3. 接口明細(全部場景合計)\n").append(mdTable(sorted(c.samplers))).append("\n");
        if (!c.transactions.isEmpty())
            b.append("## 4. 事務明細\n").append(mdTable(sorted(c.transactions))).append("\n");

        b.append("## 5. 錯誤與異常(按次數降序)\n");
        List<Err> es = sortedErrors();
        if (es.isEmpty()) b.append("無錯誤。\n");
        int i = 0;
        for (Err e : es) {
            if (++i > 30) { b.append("...其餘 ").append(es.size() - 30).append(" 類已省略(見 summary.json)\n"); break; }
            b.append(i).append(". [").append(e.category()).append("] ").append(e.count.sum()).append(" 次 | 線程組: ").append(e.group).append(" | 接口: ").append(e.label)
                    .append(" | code: ").append(e.code).append(" | msg: ").append(cut(e.message, 150));
            if (!e.failureMessage.isEmpty()) b.append(" | 斷言: ").append(cut(e.failureMessage, 150));
            if (!e.snippet.isEmpty()) b.append("\n   - 響應片段: `").append(cut(e.snippet.replace("`", "'"), 200)).append("`");
            b.append("\n");
        }

        b.append("\n## 6. 響應碼分佈\n");
        c.codes.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue().sum(), x.getValue().sum()))
                .forEach(e -> b.append("- ").append(e.getKey()).append(": ").append(e.getValue().sum()).append("\n"));

        b.append("\n## 7. 時序數據(≤60 點;t=相對秒)\n|t(s)|TPS|平均RT|最大RT|錯誤率%|線程|\n|---|---|---|---|---|---|\n");
        for (double[] p : points(60))
            b.append("|").append((long) p[0]).append("|").append(f(p[1])).append("|").append(f(p[2])).append("|")
                    .append((long) p[5]).append("|").append(f(p[3])).append("|").append((int) p[4]).append("|\n");

        // ---- 8. 響應時間分佈
        String[] bn = bucketNames();
        b.append("\n## 8. 響應時間分佈(各區間佔比 %;區間單位 ms)\n|接口|樣本數|");
        for (String x : bn) b.append(x).append("|");
        b.append("\n|---|---|");
        for (int q = 0; q < bn.length; q++) b.append("---|");
        b.append("\n");
        mdDistRow(b, "全部", c.total);
        for (Agg a : byCount(c.samplers, 30)) mdDistRow(b, a.label, a);
        if (c.groups.size() > 1) {
            b.append("\n各場景:\n");
            for (Agg a : byCount(c.groups, 30)) mdDistRow(b, "場景 " + a.label, a);
        }

        // ---- 9. 場景趨勢
        if (c.groups.size() > 1) {
            Trend tr = groupTrend(30);
            b.append("\n## 9. 場景趨勢(≤30 點;t=相對秒;「-」=該區間無樣本)\n");
            for (int k = 0; k < tr.names.size(); k++) {
                b.append("\n### 場景:").append(tr.names.get(k)).append("\n|t(s)|TPS|平均RT|最大RT|錯誤率%|\n|---|---|---|---|---|\n");
                for (int q = 0; q < tr.t.length; q++)
                    b.append("|").append((long) tr.t[q]).append("|").append(f(tr.tps.get(k)[q])).append("|")
                            .append(nan(tr.avg.get(k)[q], 0)).append("|").append(nan(tr.max.get(k)[q], 0)).append("|")
                            .append(nan(tr.err.get(k)[q], 2)).append("|\n");
            }
        }
        return b.toString();
    }

    private void mdDistRow(StringBuilder b, String name, Agg a) {
        long[] d = dist(a);
        long tot = Math.max(1, a.hist.total());
        b.append("|").append(name.replace("|", "\\|")).append("|").append(a.count).append("|");
        for (long v : d) b.append(String.format(Locale.ROOT, "%.1f", v * 100.0 / tot)).append("|");
        b.append("\n");
    }

    private static String nan(double v, int dec) {
        if (Double.isNaN(v)) return "-";
        return dec == 0 ? "" + Math.round(v) : f(v);
    }

    private static String cut(String s, int n) {
        s = s.replace("\n", " ").replace("\r", " ");
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    // ---------------------------------------------------------------- CSV

    private String labelsCsv() {
        StringBuilder b = new StringBuilder("\uFEFFtype,group");
        for (String k : KEYS) b.append(",").append(k);
        b.append("\n");
        row(b, "total", "", c.total);
        for (Agg a : sorted(c.samplers)) row(b, "sampler", "", a);
        for (Agg a : sortedGroups()) row(b, "threadGroup", a.label, a);
        for (Agg g : sortedGroups())
            for (Agg a : sortedGroupSamplers(g.label)) row(b, "groupSampler", g.label, a);
        for (Agg a : sorted(c.transactions)) row(b, "transaction", "", a);
        return b.toString();
    }

    private void row(StringBuilder b, String type, String group, Agg a) {
        String[] cs = cells(a);
        b.append(type).append(",").append(csv(group));
        for (int i = 0; i < cs.length; i++) b.append(",").append(i == 0 ? csv(cs[i]) : cs[i]);
        b.append("\n");
    }

    private String errorsCsv() {
        StringBuilder b = new StringBuilder("\uFEFFcount,category,group,label,code,message,failureMessage,firstSeen\n");
        for (Err e : sortedErrors())
            b.append(e.count.sum()).append(",").append(csv(e.category())).append(",").append(csv(e.group)).append(",").append(csv(e.label)).append(",")
                    .append(csv(e.code)).append(",").append(csv(e.message)).append(",").append(csv(e.failureMessage))
                    .append(",").append(csv(ts(e.firstTs))).append("\n");
        return b.toString();
    }
}
