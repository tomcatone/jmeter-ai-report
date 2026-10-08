package com.example.jmeter.aireport;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 離線模式:對已有的 CSV 格式 JTL 生成報告。
 * java -cp jmeter-ai-report-1.0.0.jar com.example.jmeter.aireport.JtlReportCli result.jtl [outDir] [title] [slaP95Ms] [slaErrPct]
 */
public class JtlReportCli {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("用法: JtlReportCli <result.jtl> [outDir=ai-report] [title] [slaP95Ms=0] [slaErrPct=1] [intervalSec=5] [tw|cn] [auto|light|dark]");
            System.exit(1);
        }
        Path in = Paths.get(args[0]);
        Path out = Paths.get(args.length > 1 ? args[1] : "ai-report");
        String title = args.length > 2 ? args[2] : "JMeter 壓測報告";
        long sla = args.length > 3 ? Long.parseLong(args[3]) : 0;
        double err = args.length > 4 ? Double.parseDouble(args[4]) : 1;
        int interval = args.length > 5 ? Integer.parseInt(args[5]) : 5;
        String lang = args.length > 6 ? args[6] : "tw";
        String theme = args.length > 7 ? args[7] : "auto";

        StatsCollector c = new StatsCollector(interval * 1000L);
        try (BufferedReader br = Files.newBufferedReader(in, StandardCharsets.UTF_8)) {
            String header = br.readLine();
            if (header == null) throw new IOException("空檔案");
            if (header.startsWith("\uFEFF")) header = header.substring(1);
            Map<String, Integer> ix = new HashMap<>();
            List<String> hs = split(header);
            for (int i = 0; i < hs.size(); i++) ix.put(hs.get(i).trim(), i);
            for (String need : new String[]{"timeStamp", "elapsed", "label", "success"})
                if (!ix.containsKey(need)) throw new IOException("JTL 缺少欄位 " + need + "(請使用帶標題行的 CSV JTL)");

            String rec;
            while ((rec = readRecord(br)) != null) {
                if (rec.isEmpty()) continue;
                List<String> f = split(rec);
                try {
                    String msg = get(f, ix, "responseMessage");
                    boolean tx = msg.startsWith("Number of samples in transaction");
                    c.add(StatsCollector.groupOf(get(f, ix, "threadName")), get(f, ix, "label"), tx, lng(get(f, ix, "timeStamp")), lng(get(f, ix, "elapsed")),
                            lng(get(f, ix, "Latency")), lng(get(f, ix, "Connect")),
                            "true".equalsIgnoreCase(get(f, ix, "success")),
                            get(f, ix, "responseCode"), msg, get(f, ix, "failureMessage"), "",
                            lng(get(f, ix, "bytes")), lng(get(f, ix, "sentBytes")), (int) lng(get(f, ix, "allThreads")), (int) lng(get(f, ix, "grpThreads")));
                } catch (RuntimeException ex) {
                    // 跳過壞行
                }
            }
        }
        if (c.total.count == 0) {
            System.err.println("沒有可用的樣本");
            System.exit(2);
        }
        new ReportWriter(c, title, sla, err).style(lang, theme).write(out);
        System.out.println("報告已生成: " + out.toAbsolutePath());
    }

    private static String get(List<String> f, Map<String, Integer> ix, String k) {
        Integer i = ix.get(k);
        return i == null || i >= f.size() ? "" : f.get(i);
    }

    private static long lng(String s) {
        try { return s.isEmpty() ? 0 : Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    /** 讀取一筆記錄(支援引號內換行)。 */
    private static String readRecord(BufferedReader br) throws IOException {
        String line = br.readLine();
        if (line == null) return null;
        StringBuilder sb = new StringBuilder(line);
        while (quotes(sb) % 2 != 0) {
            String next = br.readLine();
            if (next == null) break;
            sb.append('\n').append(next);
        }
        return sb.toString();
    }

    private static int quotes(CharSequence s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '"') n++;
        return n;
    }

    private static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else q = false;
                } else cur.append(ch);
            } else if (ch == '"') q = true;
            else if (ch == ',') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }
}
