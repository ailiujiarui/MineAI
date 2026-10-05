package com.dwinovo.numen.pathing.plan;

/**
 * 许可对"这一格能不能挖或放"的答复,四种:放行、要问、拒绝、悬而未决。要问带一张凭据,拒绝带一个理由,悬而未决带
 * 一个说不清的缘由,三者模块都不解读,只原样交还给宿主——凭据随账单列出"哪几格要主人同意",理由随结局说明"为什么不许";
 * 悬而未决既不是放行也不是拒绝,规划把它当墙(这一格这一趟不动),但不记成被拒、不据此判"没路是因为不许"。
 */
public sealed interface Permit {

    /** 放行。 */
    Permit ALLOW = new Allow();

    static Permit ask(Object credential) {
        return new Ask(credential);
    }

    static Permit deny(Object reason) {
        return new Deny(reason);
    }

    static Permit pending(Object reasonOrCredential) {
        return new Pending(reasonOrCredential);
    }

    /** 放行。 */
    record Allow() implements Permit {}

    /** 要问主人;{@code credential} 是宿主自己的凭据,模块不解读。 */
    record Ask(Object credential) implements Permit {}

    /** 拒绝;{@code reason} 是宿主自己的理由,模块不解读。 */
    record Deny(Object reason) implements Permit {}

    /** 悬而未决(主人不在、到点没答复):这一格这一趟不动,但不是拒绝。 */
    record Pending(Object reasonOrCredential) implements Permit {}
}
