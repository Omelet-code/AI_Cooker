package com.cooker.t18;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/** 测试侧独立批次账本；先持久化尝试再发送，失败未知均不退额度。不是生产开关。 */
public final class SamplingLedger implements AutoCloseable {
    public enum Lane { QUERY, LLM }
    public enum State { RESERVED, SENT, SUCCEEDED, FAILED, UNKNOWN }
    public static final class Stopped extends RuntimeException {
        Stopped() { super("采样已停止或额度不足，禁止追加调用"); }
    }
    public static final class Ticket {
        final String action;
        final Lane lane;
        State state = State.RESERVED;
        Ticket(String action, Lane lane) { this.action = action; this.lane = lane; }
        public State state() { return state; }
    }
    private final String batch;
    private final FileChannel file;
    private final Map<Lane, Integer> methodCaps, httpCaps;
    private final Map<Lane, Integer> methods = new EnumMap<>(Lane.class), http = new EnumMap<>(Lane.class);
    private final Map<String, Ticket> tickets = new HashMap<>();
    private boolean stopped;
    private long sequence;

    public SamplingLedger(String batch, Path path, int queryMethods, int queryHttp, int llmMethods, int llmHttp) throws IOException {
        if (!batch.matches("[A-Za-z0-9_-]{1,80}") || Math.min(Math.min(queryMethods, queryHttp), Math.min(llmMethods, llmHttp)) < 0)
            throw new IllegalArgumentException("批次标识或额度无效");
        this.batch = batch;
        this.methodCaps = Map.of(Lane.QUERY, queryMethods, Lane.LLM, llmMethods);
        this.httpCaps = Map.of(Lane.QUERY, queryHttp, Lane.LLM, llmHttp);
        file = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        event("BATCH_CREATED", null);
    }
    public synchronized Ticket reserve(String action, Lane lane) {
        check();
        if (action == null || !action.matches("[A-Za-z0-9_-]{1,80}") || lane == null || tickets.containsKey(action)) {
            stop(); throw new Stopped();
        }
        if (methods(lane) >= methodCaps.get(lane)) { stop(); throw new Stopped(); }
        Ticket ticket = new Ticket(action, lane);
        event("METHOD_RESERVED", ticket);
        methods.merge(lane, 1, Integer::sum);
        tickets.put(action, ticket);
        return ticket;
    }
    public synchronized void beforeHttp(Ticket ticket) {
        check();
        if (ticket == null || tickets.get(ticket.action) != ticket || ticket.state != State.RESERVED
                || http(ticket.lane) >= httpCaps.get(ticket.lane)) { stop(); throw new Stopped(); }
        event("HTTP_RESERVED", ticket);
        http.merge(ticket.lane, 1, Integer::sum);
        ticket.state = State.SENT;
    }
    public synchronized void finish(Ticket ticket, State state) {
        if (ticket == null || tickets.get(ticket.action) != ticket || ticket.state != State.SENT
                || !Set.of(State.SUCCEEDED, State.FAILED, State.UNKNOWN).contains(state)) { stop(); throw new Stopped(); }
        event(state.name(), ticket);
        ticket.state = state;
        if (state != State.SUCCEEDED) stop();
    }
    public synchronized void stop() {
        if (!stopped) { stopped = true; event("GLOBAL_STOP", null); }
    }
    public synchronized void check() { if (stopped) throw new Stopped(); }
    public synchronized boolean stopped() { return stopped; }
    public synchronized int methods(Lane lane) { return methods.getOrDefault(lane, 0); }
    public synchronized int http(Lane lane) { return http.getOrDefault(lane, 0); }
    private void event(String state, Ticket ticket) {
        try {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("batchId", batch); row.put("sequence", ++sequence); row.put("event", state);
            if (ticket != null) { row.put("actionId", ticket.action); row.put("lane", ticket.lane.name()); }
            row.put("usage", null);
            byte[] bytes = (new ObjectMapper().writeValueAsString(row) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) file.write(buffer);
            file.force(true);
        } catch (IOException e) {
            stopped = true;
            throw new Stopped();
        }
    }
    // 不支持进程重启后续跑：旧文件CREATE_NEW失败；必须另行核对既有结果，不能恢复额度。
    @Override public synchronized void close() throws IOException {
        for (Ticket t : tickets.values()) if (t.state == State.SENT) { event("UNKNOWN", t); t.state = State.UNKNOWN; stopped = true; }
        stopped = true;
        file.close();
    }
}
