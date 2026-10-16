import { Alert, Button, Typography } from 'antd';
import { toApiError } from '../../api/errors';
import type { useAdvertiserPermissions } from '../../hooks/useAdvertiserPermissions';

export default function PermissionNotice({
  access,
}: {
  access: ReturnType<typeof useAdvertiserPermissions>;
}) {
  if (access.query.isError)
    return (
      <Alert
        type="warning"
        showIcon
        message="操作权限暂时无法确认"
        description={toApiError(access.query.error).message}
        action={<Button onClick={() => access.query.refetch()}>重试权限查询</Button>}
      />
    );
  if (access.query.isPending || access.query.isFetching)
    return <Typography.Text type="secondary">正在确认操作权限…</Typography.Text>;
  if (access.query.data && Object.values(access.query.data).every((allowed) => !allowed))
    return (
      <Typography.Text type="secondary">当前作用域仅可查看，未授予编辑或审核权限。</Typography.Text>
    );
  return null;
}
