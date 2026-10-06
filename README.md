# JMeter AI Report Generator

壓測結束後自動生成報告的 JMeter Listener 插件,附帶可直接上傳 AI 分析的數據檔。

## 編譯與安裝
```bash
mvn clean package
cp target/jmeter-ai-report-1.0.0.jar $JMETER_HOME/lib/ext/
# 重啟 JMeter
```
需要 JDK 11+、JMeter 5.5+(pom 中版本可調整)。

## 使用
**GUI / 非 GUI 皆可**:測試計劃 → 添加 → 監聽器 → `AI Report Generator`。
設定:報告標題、輸出目錄、SLA(P95 上限、錯誤率上限)、時序間隔、錯誤響應片段長度。

```bash
jmeter -n -t plan.jmx          # 結束後自動在 <輸出目錄>/report_時間戳/ 生成報告
```

**離線(已有 JTL,需 CSV 格式且含標題行)**:
```bash
java -cp jmeter-ai-report-1.0.0.jar com.example.jmeter.aireport.JtlReportCli \
     result.jtl out "我的壓測" 800 1 5
#    JTL      輸出目錄 標題   P95ms 錯誤率% 間隔秒
```

## 產出檔案
| 檔案 | 用途 |
|---|---|
| `report.html` | 人看:概覽卡片、4 張趨勢圖、接口/事務明細、錯誤表、響應碼分佈(無外部依賴,離線可開) |
| `ai_analysis_data.md` | **上傳給 AI**:內含分析任務提示詞 + 精簡數據(概覽/明細/錯誤/60 點時序) |
| `summary.json` | 完整結構化數據(API 調用 AI 或自建分析用) |
| `labels.csv` / `errors.csv` | Excel / 二次處理 |

## 指標
樣本數、失敗數、錯誤率、avg/min/max、P50/P90/P95/P99、TPS、接收/發送 KB/s、平均 Latency、平均 Connect、
最大並發線程、響應碼分佈、錯誤分類(異常/4xx/5xx/斷言失敗)、時序(TPS/RT/錯誤率/線程)。

## 設計說明
- 事務(Transaction Controller 的父樣本)單獨統計,**不計入 TOTAL、時序、錯誤**,避免與子請求重複計算。
- 百分位數用近似直方圖(<2s 精度 1ms,<20s 精度 10ms,<300s 精度 100ms),記憶體固定、不隨樣本數增長。
- 錯誤按「接口+響應碼+訊息」去重,最多 500 類,並保留首次出現的響應片段。
- 分散式測試:Listener 在主控端統計,所有 slave 結果都會匯總。
- 局限:AI 只能看到客戶端視角,結論需配合服務端 CPU/記憶體/GC/DB 指標驗證。

## 主題與語言
`report.html` 右上角可切換:主題(🖥 跟隨系統 / ☀️ 白天 / 🌙 暗夜)與語言(繁 / 简)。選擇會記在瀏覽器裡。
監聽器介面的「報告預設語言 / 預設主題」決定第一次打開時的樣子;CLI 追加兩個參數:`... 5 cn dark`。
