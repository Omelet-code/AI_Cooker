package com.cooker.retrieval.diagnostic;

import com.cooker.retrieval.dto.RecipeSearchDocumentChunk;
import com.cooker.retrieval.service.impl.EmbeddingProviderException;
import com.cooker.retrieval.service.impl.IndexRunControl;
import com.cooker.retrieval.service.impl.IndexRunStoppedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 仅显式收费任务使用的追加账本。出站前fsync；崩溃/超时仍占额度，不重置或覆盖未知现场。 */
public final class BoundedIndexLedger implements IndexRunControl, AutoCloseable {
    public record Event(int sequence, String time, String type, String mode, List<String> chunks, Long usage, String reason) { }
    private final ObjectMapper json = new ObjectMapper();
    private final Path journal;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final IndexPlan plan;
    private final Map<String, IndexPlan.Item> planned = new HashMap<>();
    private final Map<String, Integer> counts = new HashMap<>();
    private final Set<String> successful = new HashSet<>();
    private final List<Event> events = new ArrayList<>();
    private List<RecipeSearchDocumentChunk> prepared = List.of();
    private List<String> active = List.of();
    private String mode;
    private int httpAttempts;
    private int failuresInARow;
    private boolean stopped;
    private boolean firstStarted;
    private boolean firstFinished;
    private boolean recoverStarted;
    private boolean modeFinished;
    private final long intervalMillis;
    private long lastSent;

    public BoundedIndexLedger(Path directory, IndexPlan current, long intervalMillis) throws IOException {
        Files.createDirectories(directory);
        lockChannel = FileChannel.open(directory.resolve("run.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = lockChannel.tryLock(); }
        catch (OverlappingFileLockException exception) { acquired = null; }
        if (acquired == null) { lockChannel.close(); throw stop("已有索引进程持有互斥锁，禁止双进程收费", false); }
        lock = acquired; journal = directory.resolve("attempts.jsonl"); plan = current; this.intervalMillis = intervalMillis;
        try {
            Path saved = directory.resolve("plan.json");
            if (Files.exists(saved)) IndexPlan.require(current.equals(json.readValue(saved.toFile(), IndexPlan.class)), "事实或配置漂移，禁止复用费用账本");
            else {
                IndexPlan.require(!Files.exists(journal), "账本存在但预检计划缺失，禁止重置费用计数");
                byte[] bytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(current);
                try (FileChannel file = FileChannel.open(saved, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) file.write(buffer); file.force(true);
                }
            }
            for (IndexPlan.Item item : plan.items()) planned.put(item.id(), item);
            if (Files.exists(journal)) for (String line : Files.readAllLines(journal)) {
                Event event = json.readValue(line, Event.class);
                IndexPlan.require(event.sequence() == events.size() + 1, "费用账本序列异常，禁止覆盖现场");
                apply(event); events.add(event);
            }
        } catch (IOException | RuntimeException exception) { close(); throw exception; }
    }

    public void begin(String requestedMode) {
        if (stopped || !active.isEmpty()) throw stop("前次全局停止或发送结果未知，禁止自动恢复", false);
        if ("execute".equals(requestedMode)) {
            if (firstStarted) throw stop("首轮已使用，禁止再次执行或重置账本", false);
        } else if ("recover".equals(requestedMode)) {
            if (!firstFinished || recoverStarted) throw stop("恢复仅允许在首轮完成后使用一次", false);
        } else throw stop("收费模式必须显式指定execute或recover", false);
        mode = requestedMode; modeFinished = false; append("BEGIN", List.of(), null, "");
    }
    public void finish() {
        if (!active.isEmpty() || stopped) throw stop("发送结果未闭合或全局停止，不能报告完成", false);
        append("END", List.of(), null, ""); modeFinished = true;
    }

    @Override public void checkRecipe(List<RecipeSearchDocumentChunk> chunks) {
        if (chunks.isEmpty()) throw stop("菜谱块为空，停止收费", false);
        long recipeId = chunks.getFirst().recipeId();
        if (planned.values().stream().filter(item -> item.recipeId() == recipeId).count() != chunks.size()) throw stop("每菜块集合漂移，停止收费", false);
        for (RecipeSearchDocumentChunk chunk : chunks) {
            IndexPlan.Item item = planned.get(id(chunk));
            if (item == null || !item.matches(chunk)) throw stop("事实或权限元数据漂移，停止收费", false);
        }
    }
    @Override public void prepareBatch(List<RecipeSearchDocumentChunk> chunks) { prepared = List.copyOf(chunks); }
    @Override public boolean allowCleanup() { return false; }

    @Override public void beforeSend(List<String> texts) {
        if (mode == null || modeFinished || stopped || !active.isEmpty()) throw stop("费用运行未开启或已有未闭合尝试", false);
        if (texts.isEmpty() || texts.size() > 10 || !texts.equals(prepared.stream().map(RecipeSearchDocumentChunk::content).toList())) throw stop("HTTP文本与受控块批次不一致", false);
        List<String> keys = prepared.stream().map(BoundedIndexLedger::id).toList();
        if (keys.stream().distinct().count() != keys.size() || prepared.stream().map(RecipeSearchDocumentChunk::recipeId).distinct().count() != 1) throw stop("批次包含重复块或跨菜合批", false);
        int limit = "execute".equals(mode) ? 1 : 2;
        for (RecipeSearchDocumentChunk chunk : prepared) {
            IndexPlan.Item item = planned.get(id(chunk));
            if (item == null || !item.matches(chunk) || counts.getOrDefault(id(chunk), 0) >= limit || successful.contains(id(chunk))) throw stop("块已成功、超过发送次数或输入漂移，禁止重复收费", false);
        }
        long modeAttempts = events.stream().filter(event -> event.type().equals("SEND") && event.mode().equals(mode)).count();
        if (httpAttempts >= 2 * plan.firstHttpBound() || modeAttempts >= plan.firstHttpBound()) throw stop("HTTP授权上界已用尽", false);
        long wait = intervalMillis - (System.nanoTime() - lastSent) / 1_000_000;
        if (lastSent != 0 && wait > 0) try { Thread.sleep(wait); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw stop("索引发送等待被中断", false); }
        // 记账先于HTTP，持久化失败不发送；网络超时也已经占一个尝试。
        append("SEND", keys, null, ""); lastSent = System.nanoTime();
    }
    @Override public void succeeded(Long tokens) { append("SUCCESS", active, tokens, ""); }
    @Override public void failed(EmbeddingProviderException failure) {
        append("FAILURE", active, null, failure.kind().name());
        if (failure.kind().global() || failuresInARow >= 3) {
            append("STOP", List.of(), null, failure.kind().name());
            throw stop("索引全局停止：" + failure.kind().name(), true);
        }
    }
    @Override public void writeFailed() {
        append("STOP", List.of(), null, "DATABASE_WRITE");
        throw stop("已获得向量但投影写入失败，保留现场且禁止再次收费生成", false);
    }
    public Map<String, Object> summary() {
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("httpAttempts", httpAttempts); summary.put("sentTexts", counts.values().stream().mapToInt(Integer::intValue).sum());
        summary.put("maxSendsPerChunk", counts.values().stream().mapToInt(Integer::intValue).max().orElse(0));
        summary.put("firstHttp", events.stream().filter(event -> event.type().equals("SEND") && event.mode().equals("execute")).count());
        summary.put("recoverHttp", events.stream().filter(event -> event.type().equals("SEND") && event.mode().equals("recover")).count());
        summary.put("firstTexts", events.stream().filter(event -> event.type().equals("SEND") && event.mode().equals("execute")).mapToInt(event -> event.chunks().size()).sum());
        summary.put("recoverTexts", events.stream().filter(event -> event.type().equals("SEND") && event.mode().equals("recover")).mapToInt(event -> event.chunks().size()).sum());
        summary.put("observedUsageTokens", events.stream().filter(event -> event.type().equals("SUCCESS") && event.usage() != null).mapToLong(Event::usage).sum());
        summary.put("usageUnknownResponses", events.stream().filter(event -> event.type().equals("SUCCESS") && event.usage() == null).count());
        summary.put("attemptsWithUnknownUsage", httpAttempts - events.stream().filter(event -> event.type().equals("SUCCESS") && event.usage() != null).count());
        summary.put("successResponses", events.stream().filter(event -> event.type().equals("SUCCESS")).count());
        summary.put("failedResponses", events.stream().filter(event -> event.type().equals("FAILURE")).count());
        summary.put("stopped", stopped); summary.put("unresolvedAttempt", !active.isEmpty());
        return summary;
    }
    private void append(String type, List<String> chunks, Long usage, String reason) {
        Event event = new Event(events.size() + 1, Instant.now().toString(), type, mode, List.copyOf(chunks), usage, reason);
        try (FileChannel file = FileChannel.open(journal, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer bytes = ByteBuffer.wrap((json.writeValueAsString(event) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) file.write(bytes); file.force(true);
        } catch (IOException exception) { throw stop("费用账本持久化失败，禁止继续发送", false); }
        apply(event); events.add(event);
    }
    private void apply(Event event) {
        switch (event.type()) {
            case "BEGIN" -> { mode = event.mode(); if (mode.equals("execute")) firstStarted = true; else recoverStarted = true; }
            case "END" -> { if (event.mode().equals("execute")) firstFinished = true; modeFinished = true; }
            case "SEND" -> { httpAttempts++; for (String key : event.chunks()) counts.merge(key, 1, Integer::sum); active = event.chunks(); }
            case "SUCCESS" -> { successful.addAll(event.chunks()); active = List.of(); failuresInARow = 0; }
            case "FAILURE" -> { active = List.of(); failuresInARow++; }
            case "STOP" -> stopped = true;
            default -> throw stop("费用账本出现未知事件，禁止继续", false);
        }
    }
    private static String id(RecipeSearchDocumentChunk chunk) { return chunk.recipeId() + "/" + chunk.key(); }
    private static IndexRunStoppedException stop(String message, boolean sent) { return new IndexRunStoppedException(message, sent); }
    @Override public void close() throws IOException { if (lock.isValid()) lock.release(); lockChannel.close(); }
}
