package com.cooker.evaluation.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import com.cooker.evaluation.transport.SyncModelTransport.Lane;

/** 整个进程共用独立批准批次。只追加持久尝试，不恢复或退还失败额度。 */
public final class EvaluationBatch implements AutoCloseable {
    public static final class Stopped extends RuntimeException {
        public Stopped() { super("受控评测已停止、缺少批准上下文或额度不足"); }
    }
    public static final class Ticket {
        final Lane lane; final long sequence; boolean sent; boolean finished;
        Ticket(Lane lane,long sequence) { this.lane=lane;this.sequence=sequence; }
    }
    private final String batchId, authorizationId;
    private final FileChannel file;
    private final Map<Lane,Integer> methodCaps, httpCaps;
    private final Map<Lane,Integer> methods=new EnumMap<>(Lane.class),http=new EnumMap<>(Lane.class);
    private final Set<Ticket> tickets=Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean stopped;
    private long sequence;
    public EvaluationBatch(String batchId,String authorizationId,Path ledger,int queryMethods,int queryHttp,int llmMethods,int llmHttp)throws IOException {
        if(!safe(batchId)||!safe(authorizationId)||ledger==null||!ledger.isAbsolute()
                ||Arrays.stream(new int[]{queryMethods,queryHttp,llmMethods,llmHttp}).anyMatch(v->v<0||v>1000))
            throw new IllegalArgumentException("评测批次、授权标识、绝对账本路径或额度无效");
        this.batchId=batchId;this.authorizationId=authorizationId;
        methodCaps=Map.of(Lane.QUERY,queryMethods,Lane.LLM,llmMethods);httpCaps=Map.of(Lane.QUERY,queryHttp,Lane.LLM,llmHttp);
        file=FileChannel.open(ledger,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
        try { event("BATCH_CREATED",null); } catch(RuntimeException e) {file.close();throw e;}
    }
    private static boolean safe(String value){return value!=null&&value.matches("[A-Za-z0-9_-]{1,80}");}
    public synchronized void check(){if(stopped)throw new Stopped();}
    public synchronized Ticket reserve(Lane lane){
        check();if(lane==null||methods(lane)>=methodCaps.get(lane)){stop();throw new Stopped();}
        var ticket=new Ticket(lane,++sequence);event("METHOD_RESERVED",ticket);methods.merge(lane,1,Integer::sum);tickets.add(ticket);return ticket;
    }
    public synchronized void beforeHttp(Ticket t){
        check();if(t==null||!tickets.contains(t)||t.sent||t.finished||http(t.lane)>=httpCaps.get(t.lane)){stop();throw new Stopped();}
        event("HTTP_RESERVED",t);http.merge(t.lane,1,Integer::sum);t.sent=true;
    }
    public synchronized void complete(Ticket t){
        check();if(t==null||!tickets.contains(t)||t.finished||!t.sent){stop();throw new Stopped();}
        event("SUCCEEDED",t);t.finished=true;
    }
    public synchronized void failed(Ticket t,boolean unknown){
        if(t!=null&&tickets.contains(t)&&!t.finished){event(unknown?"UNKNOWN":"FAILED",t);t.finished=true;}
        stop();
    }
    public synchronized void stop(){if(!stopped){stopped=true;event("GLOBAL_STOP",null);}}
    public synchronized void guideRejected(){
        if(stopped)return;
        try{event("GUIDE_REJECTED",null);}finally{stop();}
    }
    public synchronized boolean stopped(){return stopped;}
    public synchronized int methods(Lane lane){return methods.getOrDefault(lane,0);}
    public synchronized int http(Lane lane){return http.getOrDefault(lane,0);}
    private void event(String state,Ticket t){
        try{
            var row=new LinkedHashMap<String,Object>();row.put("batchId",batchId);row.put("authorizationId",authorizationId);
            row.put("event",state);if(t!=null){row.put("methodSequence",t.sequence);row.put("lane",t.lane.name());}
            row.put("usage",null);
            var b=ByteBuffer.wrap((new ObjectMapper().writeValueAsString(row)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            while(b.hasRemaining())file.write(b);file.force(true);
        }catch(IOException e){stopped=true;throw new Stopped();}
    }
    @Override public synchronized void close()throws IOException{
        try{for(var t:tickets)if(!t.finished){event(t.sent?"UNKNOWN":"FAILED",t);t.finished=true;}stop();}
        finally{stopped=true;file.close();}
    }
}
