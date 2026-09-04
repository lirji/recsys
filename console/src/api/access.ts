import type { Role } from './auth';
import type { NavDestination } from './nav';

export function canAccess(dest: Pick<NavDestination, 'roles'>, roles: Role[]): boolean {
  if (!dest.roles || dest.roles.length === 0) return true;
  return dest.roles.some((r) => roles.includes(r));
}

export function hasAnyRole(userRoles: Role[] | undefined, required: Role[]): boolean {
  if (!required.length) return true;
  return (userRoles ?? []).some((r) => required.includes(r));
}
