package com.yourorg.omp.security;

import com.yourorg.omp.entity.User;

/**
 * 数据查询范围 —— 将三级数据隔离统一抽象为两个可空过滤条件。
 *
 * <p>查询时统一使用：
 * <pre>
 *   WHERE (:userId IS NULL OR x.user_id = :userId)
 *     AND (:tenantId IS NULL OR x.tenant_id = :tenantId)
 * </pre>
 *
 * <table>
 *   <tr><th>身份</th><th>userId</th><th>tenantId</th><th>效果</th></tr>
 *   <tr><td>super_admin</td><td>null</td><td>null</td><td>全部数据</td></tr>
 *   <tr><td>admin</td><td>null</td><td>本租户</td><td>本租户全部</td></tr>
 *   <tr><td>user</td><td>本人</td><td>null</td><td>仅本人</td></tr>
 * </table>
 *
 * @param userId   非 null 时仅查该用户的数据
 * @param tenantId 非 null 时仅查该租户的数据
 */
public record DataScope(Long userId, Long tenantId) {

    /** 根据用户身份级别推导查询范围。 */
    public static DataScope of(User user) {
        if (user.isSuperAdmin()) {
            return new DataScope(null, null);
        }
        if (user.isAdmin()) {
            return new DataScope(null, user.getTenantId());
        }
        return new DataScope(user.getId(), null);
    }

    /** 是否不受限（超级管理员）—— 可用于跳过过滤的快捷判断。 */
    public boolean isUnrestricted() {
        return userId == null && tenantId == null;
    }
}
