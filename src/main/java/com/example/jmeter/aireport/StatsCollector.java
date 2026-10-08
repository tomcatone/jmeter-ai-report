package com.example.jmeter.aireport;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * 線程安全的指標聚合器。Listener 與 CLI(JTL 解析)共用同一份邏輯。
 * 事務(Transaction)樣本單獨統計,不計入 TOTAL / 時序 / 錯誤,避免與子請求重複計算。
 */
public class StatsCollector {

    public static final int MAX_ERROR_KEYS = 500;
    /** 每個維度(線程組/接口)最多保留多少條獨立時序,避免接口過多時記憶體膨脹。 */
    public static final int MAX_SERIES_KEYS = 100;

    public final long intervalMs;
    public final LongAccumulator minTs = new LongAccumulator(Math::min, Long.MAX_VALUE);
    public final LongAccumulator maxEndTs = new LongAccumulator(Math::max, 0L);
    public volatile int maxThreads;

    public final Agg total = new Agg("TOTAL", false);
    public final Map<String, Agg> samplers = new ConcurrentHashMap<>();
    public final Map<String, Agg> transactions = new ConcurrentHashMap<>();
    /** 線程組/場景維度:整體、各接口、最大線程數。 */
    public final Map<String, Agg> groups = new ConcurrentHashMap<>();
    public final Map<String, Map<String, Agg>> groupSamplers = new ConcurrentHashMap<>();
    public final Map<String, Integer> groupThreads = new ConcurrentHashMap<>();
    public final Map<String, Err> errors = new ConcurrentHashMap<>();
    public final Map<String, LongAdder> codes = new ConcurrentHashMap<>();
    public final ConcurrentSkipListMap<Long, Slice> series = new ConcurrentSkipListMap<>();
    public final Map<String, ConcurrentSkipListMap<Long, Slice>> groupSeries = new ConcurrentHashMap<>();
    public final Map<String, ConcurrentSkipListMap<Long, Slice>> labelSeries = new ConcurrentHashMap<>();

    public StatsCollector(long intervalMs) {
        this.intervalMs = Math.max(1000, intervalMs);
    }

    // ---------------------------------------------------------------- API

    /** 線程名格式 "<線程組名> <組序號>-<線程序號>",取出線程組名。 */
    public static String groupOf(String threadName) {
        if (threadName == null || threadName.isEmpty()) return "(unknown)";
        int sp = threadName.lastIndexOf(' ');
        if (sp > 0 && threadName.substring(sp + 1).matches("\\d+-\\d+")) return threadName.substring(0, sp);
        return threadName;
    }

    public static String errKey(String group, String label, String code, String msg) {
        String m = msg == null ? "" : (msg.length() > 200 ? msg.substring(0, 200) : msg);
        return group + "\u0001" + label + "\u0001" + code + "\u0001" + m;
    }

    public void add(String group, String label, boolean transaction, long ts, long elapsed, long latency,
                    long connect, boolean ok, String code, String msg, String failMsg,
                    String snippet, long bytes, long sent, int threads, int grpThreads) {
        code = code == null ? "" : code;
        if (group == null || group.isEmpty()) group = "(unknown)";
        final String grp = group;
        long end = ts + elapsed;
        if (threads > maxThreads) maxThreads = threads;

        if (transaction) {
            transactions.computeIfAbsent(label, l -> new Agg(l, true))
                    .add(ts, elapsed, latency, connect, ok, bytes, sent);
            return;
        }
        minTs.accumulate(ts);
        maxEndTs.accumulate(end);
        total.add(ts, elapsed, latency, connect, ok, bytes, sent);
        samplers.computeIfAbsent(label, l -> new Agg(l, false))
                .add(ts, elapsed, latency, connect, ok, bytes, sent);
        groups.computeIfAbsent(grp, g -> new Agg(g, false))
                .add(ts, elapsed, latency, connect, ok, bytes, sent);
        groupSamplers.computeIfAbsent(grp, g -> new ConcurrentHashMap<>())
                .computeIfAbsent(label, l -> new Agg(l, false))
                .add(ts, elapsed, latency, connect, ok, bytes, sent);
        groupThreads.merge(grp, grpThreads, Math::max);
        codes.computeIfAbsent(code.isEmpty() ? "(empty)" : code, k -> new LongAdder()).increment();
        final long bucket = ts / intervalMs * intervalMs;
        series.computeIfAbsent(bucket, k -> new Slice()).add(elapsed, ok, threads, bytes);
        addSeries(groupSeries, grp, bucket, elapsed, ok, threads, bytes);
        addSeries(labelSeries, label, bucket, elapsed, ok, threads, bytes);

        if (!ok) {
            String key = errKey(grp, label, code, msg);
            Err e = errors.get(key);
            if (e == null) {
                final String fKey = errors.size() >= MAX_ERROR_KEYS ? "OTHER" : key;
                final String fCode = code;
                e = errors.computeIfAbsent(fKey, k -> new Err(grp, label, fCode, msg, failMsg, snippet, ts));
            }
            e.count.increment();
        }
    }

    private void addSeries(Map<String, ConcurrentSkipListMap<Long, Slice>> m, String key, long bucket,
                           long elapsed, boolean ok, int threads, long bytes) {
        ConcurrentSkipListMap<Long, Slice> s = m.get(key);
        if (s == null) {
            if (m.size() >= MAX_SERIES_KEYS) return;
            s = m.computeIfAbsent(key, k -> new ConcurrentSkipListMap<>());
        }
        s.computeIfAbsent(bucket, k -> new Slice()).add(elapsed, ok, threads, bytes);
    }

    // ----------------------------------------------------------- data types

    /** 近似直方圖:<2s 1ms 精度,<20s 10ms,<300s 100ms。每個 label 約 26KB。 */
    public static final class Histogram {
        private final int[] bins = new int[6600];
        private long n;

        private static int idx(long v) {
            if (v < 0) v = 0;
            if (v < 2000) return (int) v;
            if (v < 20000) return 2000 + (int) ((v - 2000) / 10);
            if (v < 300000) return 3800 + (int) ((v - 20000) / 100);
            return 6599;
        }

        private static long val(int i) {
            if (i < 2000) return i;
            if (i < 3800) return 2000 + (long) (i - 2000) * 10;
            return 20000 + (long) (i - 3800) * 100;
        }

        void add(long v) { bins[idx(v)]++; n++; }

        public long total() { return n; }

        /** 響應時間 < v 的樣本數(v 為 ≤2000 的整數,或 ≥2000 時為 10 的倍數,結果精確)。 */
        public long countLessThan(long v) {
            long cum = 0;
            for (int i = 0; i < bins.length; i++) {
                if (val(i) >= v) break;
                cum += bins[i];
            }
            return cum;
        }

        public long percentile(double p) {
            if (n == 0) return 0;
            long target = (long) Math.ceil(p / 100.0 * n);
            long cum = 0;
            for (int i = 0; i < bins.length; i++) {
                cum += bins[i];
                if (cum >= target) return val(i);
            }
            return val(bins.length - 1);
        }
    }

    public static final class Agg {
        public final String label;
        public final boolean transaction;
        public long count, errors, sumElapsed, maxElapsed, sumLatency, sumConnect, bytes, sent;
        public long minElapsed = Long.MAX_VALUE, firstTs = Long.MAX_VALUE, lastEnd;
        public final Histogram hist = new Histogram();

        Agg(String label, boolean transaction) {
            this.label = label;
            this.transaction = transaction;
        }

        synchronized void add(long ts, long elapsed, long latency, long connect,
                              boolean ok, long b, long s) {
            count++;
            if (!ok) errors++;
            sumElapsed += elapsed;
            sumLatency += latency;
            sumConnect += connect;
            bytes += b;
            sent += s;
            if (elapsed < minElapsed) minElapsed = elapsed;
            if (elapsed > maxElapsed) maxElapsed = elapsed;
            if (ts < firstTs) firstTs = ts;
            if (ts + elapsed > lastEnd) lastEnd = ts + elapsed;
            hist.add(elapsed);
        }

        public double durationSec() { return count == 0 ? 0 : Math.max(1, lastEnd - firstTs) / 1000.0; }
        public double avg() { return count == 0 ? 0 : (double) sumElapsed / count; }
        public double errPct() { return count == 0 ? 0 : errors * 100.0 / count; }
        public double tps() { return count == 0 ? 0 : count / durationSec(); }
        public double recvKBps() { return count == 0 ? 0 : bytes / 1024.0 / durationSec(); }
        public double sentKBps() { return count == 0 ? 0 : sent / 1024.0 / durationSec(); }
        public double avgLatency() { return count == 0 ? 0 : (double) sumLatency / count; }
        public double avgConnect() { return count == 0 ? 0 : (double) sumConnect / count; }
        public long min() { return count == 0 ? 0 : minElapsed; }
    }

    public static final class Slice {
        public long count, errors, sumElapsed, maxElapsed, bytes;
        public int threads;

        synchronized void add(long elapsed, boolean ok, int th, long b) {
            count++;
            if (!ok) errors++;
            sumElapsed += elapsed;
            if (elapsed > maxElapsed) maxElapsed = elapsed;
            if (th > threads) threads = th;
            bytes += b;
        }
    }

    public static final class Err {
        public final String group, label, code, message, failureMessage, snippet;
        public final long firstTs;
        public final LongAdder count = new LongAdder();

        Err(String group, String label, String code, String message, String failureMessage, String snippet, long firstTs) {
            this.group = group;
            this.label = label;
            this.code = code;
            this.message = message == null ? "" : message;
            this.failureMessage = failureMessage == null ? "" : failureMessage;
            this.snippet = snippet == null ? "" : snippet;
            this.firstTs = firstTs;
        }

        public String category() {
            if (code.startsWith("Non HTTP response code")) return "異常/連線層錯誤";
            if (code.startsWith("5")) return "HTTP 5xx 服務端錯誤";
            if (code.startsWith("4")) return "HTTP 4xx 客戶端錯誤";
            if (!failureMessage.isEmpty()) return "斷言失敗";
            return "其他";
        }
    }
}
