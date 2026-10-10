package com.cooker.conversation.mapper;

import com.cooker.conversation.dto.MealRows.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MealMapper {
    @Select("SELECT *,view_json::text AS view_json FROM meal_session WHERE id=#{meal} AND conversation_id=#{conversation} AND user_id=#{user}")
    Session get(@Param("user") long user,@Param("conversation") long conversation,@Param("meal") long meal);
    @Select("SELECT *,view_json::text AS view_json FROM meal_session WHERE id=#{meal} AND conversation_id=#{conversation} AND user_id=#{user} FOR UPDATE")
    Session lock(@Param("user") long user,@Param("conversation") long conversation,@Param("meal") long meal);
    @Select("SELECT *,parameters::text, result::text,metrics::text FROM meal_action WHERE user_id=#{user} AND conversation_id=#{conversation} AND request_id=CAST(#{request} AS uuid)")
    Action request(@Param("user") long user,@Param("conversation") long conversation,@Param("request") String request);
    @Select("SELECT *,parameters::text,result::text,metrics::text FROM meal_action WHERE user_id=#{user} AND conversation_id=#{conversation} AND meal_id=#{meal} AND id=#{action}")
    Action action(@Param("user") long user,@Param("conversation") long conversation,@Param("meal") long meal,@Param("action") long action);
    @Select("SELECT *,parameters::text,result::text,metrics::text FROM meal_action WHERE user_id=#{user} AND conversation_id=#{conversation} AND meal_id=#{meal} AND id=#{action} FOR UPDATE")
    Action lockAction(@Param("user") long user,@Param("conversation") long conversation,@Param("meal") long meal,@Param("action") long action);
    @Select("SELECT *,parameters::text,result::text,metrics::text FROM meal_action WHERE meal_id=#{meal} AND status IN ('PENDING','RUNNING') FOR UPDATE")
    Action active(long meal);
    @Select("SELECT *,parameters::text,result::text,metrics::text FROM meal_action WHERE meal_id=#{meal} AND version=#{version} AND type='SELECT' AND status='SUCCEEDED' ORDER BY id LIMIT 1")
    Action selection(@Param("meal") long meal,@Param("version") int version);
    @Select("SELECT clock_timestamp() >= deadline_at FROM meal_action WHERE id=#{action}")
    boolean expired(long action);
    @Select("SELECT id FROM meal_session WHERE user_id=#{user} AND conversation_id=#{conversation} ORDER BY updated_at DESC,id DESC LIMIT #{size} OFFSET #{offset}")
    List<Long> list(@Param("user") long user,@Param("conversation") long conversation,@Param("size") int size,@Param("offset") long offset);
    @Select("SELECT count(*) FROM meal_session WHERE user_id=#{user} AND conversation_id=#{conversation}")
    long count(@Param("user") long user,@Param("conversation") long conversation);
    @Select(value="INSERT INTO meal_session(conversation_id,user_id,profile_dining_count,status,view_json) VALUES(#{conversation},#{user},#{profile},'PENDING','{}') RETURNING id",affectData=true)
    long create(@Param("user") long user,@Param("conversation") long conversation,@Param("profile") Integer profile);
    @Select(value="INSERT INTO meal_action(meal_id,conversation_id,user_id,version,request_id,type,status,input_text,parameters,input_hash,seed,deadline_at) VALUES(#{meal},#{conversation},#{user},#{version},CAST(#{request} AS uuid),#{type},'PENDING',#{content},CAST(#{parameters} AS jsonb),#{hash},#{seed},clock_timestamp()+interval '120 seconds') RETURNING id",affectData=true)
    long register(@Param("user") long user,@Param("conversation") long conversation,@Param("meal") long meal,@Param("version") int version,@Param("request") String request,@Param("type") String type,@Param("content") String content,@Param("parameters") String parameters,@Param("hash") String hash,@Param("seed") Long seed);
    @Update("UPDATE meal_session SET current_version=#{version},status=#{status},selected_option_id=#{selected},dining_count=#{count},dining_count_source=#{source},view_json=CAST(#{view} AS jsonb),updated_at=clock_timestamp() WHERE id=#{meal}")
    void save(@Param("meal") long meal,@Param("version") int version,@Param("status") String status,@Param("selected") Long selected,@Param("count") Integer count,@Param("source") String source,@Param("view") String view);
    @Update("UPDATE meal_action SET status='RUNNING',started_at=clock_timestamp() WHERE id=#{action} AND status='PENDING' AND deadline_at>clock_timestamp()")
    int start(long action);
    @Update("UPDATE meal_action SET status=#{status},finished_at=clock_timestamp(),error_code=#{code},result=CAST(#{result} AS jsonb),metrics=CAST(#{metrics} AS jsonb) WHERE id=#{action} AND status IN ('PENDING','RUNNING')")
    int finish(@Param("action") long action,@Param("status") String status,@Param("code") String code,@Param("result") String result,@Param("metrics") String metrics);
    @Update("UPDATE meal_action SET status='SUCCEEDED',finished_at=clock_timestamp(),result=CAST(#{result} AS jsonb),metrics=CAST(#{metrics} AS jsonb) WHERE id=#{action} AND type='GUIDE' AND status='RUNNING' AND deadline_at>clock_timestamp()")
    int finishGuide(@Param("action") long action,@Param("result") String result,@Param("metrics") String metrics);
    @Select(value="INSERT INTO meal_option(meal_id,conversation_id,user_id,version,ordinal,facts) VALUES(#{meal},#{conversation},#{user},#{version},#{ordinal},CAST(#{facts} AS jsonb)) RETURNING id",affectData=true)
    long option(@Param("user") long user,@Param("conversation") long conversation,@Param("meal") long meal,@Param("version") int version,@Param("ordinal") int ordinal,@Param("facts") String facts);
    @Select("SELECT facts::text FROM meal_option WHERE id=#{option} AND meal_id=#{meal} AND conversation_id=#{conversation} AND user_id=#{user} AND version=#{version} FOR UPDATE")
    String lockOption(@Param("user") long user,@Param("conversation") long conversation,@Param("meal") long meal,@Param("version") int version,@Param("option") long option);
}
