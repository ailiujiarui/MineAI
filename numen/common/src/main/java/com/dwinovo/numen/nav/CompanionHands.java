package com.dwinovo.numen.nav;

import com.dwinovo.numen.api.permission.ConsentItem;
import com.dwinovo.numen.api.permission.Verdict;

/**
 * 权限层拒绝理由在导航一侧的登记:手每一下之前把动作交给权限层裁决,拒绝就停手并把理由交给派这一下的一方。
 * 悬而未决(主人不在)与要问(还没问过)分开记,导航据此决定等一等还是去问。
 *
 * <p>寻路已改为经 Numen API 的输入层({@code entity.Mouse})动手,权限裁决在那里完成,本类只留下这套理由类型。
 */
public final class CompanionHands {

    private CompanionHands() {}

    /** 要问主人而还没问过的一下:裁决({@link Verdict#asks})与发起征询要用的那一条。 */
    public record Unasked(Verdict verdict, ConsentItem item) {}

    /** 悬而未决的一下:主人不在、到点没答复,这一格这一下不动,但不是拒绝。 */
    public record Withheld(Verdict verdict) {}

    /** 手交回的拒绝理由里权限层的裁决;不是权限层的话为 null。 */
    public static Verdict verdict(Object refusal) {
        return switch (refusal) {
            case Verdict verdict -> verdict;
            case Unasked unasked -> unasked.verdict();
            case Withheld withheld -> withheld.verdict();
            case null, default -> null;
        };
    }
}
