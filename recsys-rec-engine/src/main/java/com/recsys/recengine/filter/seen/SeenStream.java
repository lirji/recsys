package com.recsys.recengine.filter.seen;

import com.recsys.common.behavior.LegacyBehaviorAccepted;
import org.springframework.core.env.Environment;
import java.util.*;

/** 固定流及配置绑定；即使全部关闭也校验路由，防止上线后串流。 */
public enum SeenStream {
    LEGACY, LIVE, HISTORY;
    public record Binding(SeenStream stream,String topic,String group,boolean consume) { }
    /** 三组必须不同，物理topic只允许单个合法名称及相应逻辑后缀。 */
    public static Map<SeenStream,Binding> bindings(Environment env) {
        var result=new EnumMap<SeenStream,Binding>(SeenStream.class);
        for(var stream:values()) {
            String prefix=stream==LEGACY?"recsys.behavior.":"recsys.behavior.seen."+stream.name().toLowerCase(Locale.ROOT)+"-";
            String logical=stream==LEGACY?"behavior-events":stream==LIVE?LegacyBehaviorAccepted.LIVE_TOPIC:LegacyBehaviorAccepted.HISTORY_TOPIC;
            String group=env.getProperty(prefix+(stream==LEGACY?"seen-group":"group"),"recsys-rec-engine-seen"+(stream==LEGACY?"":"-"+stream.name().toLowerCase(Locale.ROOT)+"-v1"));
            String topic=env.getProperty(prefix+"topic",logical);
            if(topic.length()>249 || !topic.matches("[a-zA-Z0-9._-]+") || topic.equals(".") || topic.equals(".."))throw new SeenRecord.Failure(SeenRecord.Code.INVALID_COMMAND);
            if(stream==LEGACY)LegacyBehaviorAccepted.requireLegacyTopic(topic);
            else if(!topic.equals(logical) && !(topic.startsWith(logical+".") && topic.length()>logical.length()+1))throw new SeenRecord.Failure(SeenRecord.Code.INVALID_COMMAND);
            if(group.isBlank() || group.length()>200 || group.contains(","))throw new SeenRecord.Failure(SeenRecord.Code.INVALID_COMMAND);
            boolean consume=env.getProperty(prefix+(stream==LEGACY?"seen-consume":"consume"),Boolean.class,false);
            result.put(stream,new Binding(stream,topic,group,consume));
        }
        if(result.values().stream().map(Binding::group).distinct().count()!=values().length)throw new SeenRecord.Failure(SeenRecord.Code.INVALID_COMMAND);
        return Collections.unmodifiableMap(result);
    }
}
