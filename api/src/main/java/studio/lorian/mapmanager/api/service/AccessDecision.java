package studio.lorian.mapmanager.api.service;

import studio.lorian.mapmanager.api.model.MapRole;
import studio.lorian.mapmanager.api.result.FailureReason;

/** 玩家进入地图的准入判断结果。
 *
 * @param allowed MMC 是否允许玩家进入
 * @param effectiveRole 授予准入权限的最高角色；公开访客准入时为 {@code null}
 * @param denialReason 拒绝进入的原因；允许进入时为 {@code null}
 */
public record AccessDecision(boolean allowed, MapRole effectiveRole, FailureReason denialReason) {
}
