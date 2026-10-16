package com.recsys.behavior.events;

import com.recsys.behavior.persistence.mapper.primary.BehaviorEventMapper.Pending;
import com.recsys.common.behavior.LegacyBehaviorAccepted;
import com.recsys.platform.behavior.LegacyBehaviorCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** owner侧验证行元数据与原字节摘要，公共wire解码规则只有一个来源。 */
public class BehaviorEventCodec extends LegacyBehaviorCodec {
    /** SQL已限制返回长度；此处再次核对身份、目标流和持久摘要，不重编码原负载。 */
    public LegacyBehaviorAccepted validate(Pending pending) {
        var event=decode(pending.key(),pending.payload());
        try {
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pending.payload().getBytes(StandardCharsets.UTF_8)));
            if (!event.eventId().equals(pending.eventId()) || !event.eventType().equals(pending.eventType())
                    || event.schemaVersion()!=pending.schemaVersion() || !event.destination().equals(pending.destination())
                    || !hash.equals(pending.payloadSha256())) throw new InvalidEvent();
            return event;
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA256不可用"); }
    }
}
