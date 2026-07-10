import { Tag } from 'antd';
import { channelColor } from './channelColors';

export default function RecallTags({ channels }: { channels?: string[] }) {
  if (!channels || channels.length === 0) return null;
  return (
    <>
      {channels.map((c) => (
        <Tag key={c} color={channelColor(c)} style={{ marginInlineEnd: 4 }}>
          {c}
        </Tag>
      ))}
    </>
  );
}
