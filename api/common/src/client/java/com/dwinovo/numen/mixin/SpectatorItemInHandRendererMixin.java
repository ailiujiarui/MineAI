package com.dwinovo.numen.mixin;

import com.dwinovo.numen.client.spectator.SpectatorClient;
import com.dwinovo.numen.client.spectator.SpectatorHandRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 观看手的装备缓存独立于主人,切目标或退出时立即作废。 */
@Mixin(ItemInHandRenderer.class)
public abstract class SpectatorItemInHandRendererMixin implements SpectatorHandRenderer {
    @Shadow private ItemStack mainHandItem;
    @Shadow private ItemStack offHandItem;
    @Shadow private float mainHandHeight;
    @Shadow private float offHandHeight;
    @Shadow private float oMainHandHeight;
    @Shadow private float oOffHandHeight;
    @Unique private AbstractClientPlayer numen$handTarget;

    @Invoker("renderArmWithItem")
    public abstract void numen$renderArmWithItem(AbstractClientPlayer target, float partialTick, float pitch,
                                               InteractionHand hand, float swing, ItemStack item, float equip,
                                               PoseStack pose, MultiBufferSource buffers, int light);

    @Override
    public void numen$resetHands() {
        numen$handTarget = null;
        Minecraft mc = Minecraft.getInstance();
        mainHandItem = mc.player == null ? ItemStack.EMPTY : mc.player.getMainHandItem();
        offHandItem = mc.player == null ? ItemStack.EMPTY : mc.player.getOffhandItem();
        mainHandHeight = oMainHandHeight = offHandHeight = oOffHandHeight = 1.0F;
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void numen$tickTargetHands(CallbackInfo ci) {
        if (!SpectatorClient.active()) return;
        ci.cancel();
        AbstractClientPlayer target = SpectatorClient.target();
        Inventory inventory = SpectatorClient.mirrorInventory();
        if (target == null || inventory == null) {
            mainHandItem = offHandItem = ItemStack.EMPTY;
            numen$handTarget = null;
            return;
        }
        ItemStack main = inventory.getSelected(), off = inventory.getItem(40);
        if (numen$handTarget != target) {
            numen$handTarget = target;
            mainHandItem = main;
            offHandItem = off;
            mainHandHeight = oMainHandHeight = offHandHeight = oOffHandHeight = 1.0F;
            return;
        }
        oMainHandHeight = mainHandHeight;
        oOffHandHeight = offHandHeight;
        if (ItemStack.matches(mainHandItem, main)) mainHandItem = main;
        if (ItemStack.matches(offHandItem, off)) offHandItem = off;
        float strength = SpectatorClient.state().attackStrength();
        mainHandHeight += Mth.clamp((mainHandItem == main ? strength * strength * strength : 0F) - mainHandHeight, -0.4F, 0.4F);
        offHandHeight += Mth.clamp((offHandItem == off ? 1F : 0F) - offHandHeight, -0.4F, 0.4F);
        if (mainHandHeight < 0.1F) mainHandItem = main;
        if (offHandHeight < 0.1F) offHandItem = off;
    }

    @Override
    public void numen$renderHands(float partialTick, PoseStack pose, MultiBufferSource.BufferSource buffers,
                                  AbstractClientPlayer target, int light) {
        if (target.isSpectator()) return;
        if (numen$handTarget != target && SpectatorClient.mirrorInventory() != null) {
            numen$handTarget = target;
            mainHandItem = SpectatorClient.mirrorInventory().getSelected();
            offHandItem = SpectatorClient.mirrorInventory().getItem(40);
            mainHandHeight = oMainHandHeight = offHandHeight = oOffHandHeight = 1.0F;
        }
        float swing = target.getAttackAnim(partialTick);
        float pitch = Mth.lerp(partialTick, target.xRotO, target.getXRot());
        InteractionHand swinging = target.swingingArm == null ? InteractionHand.MAIN_HAND : target.swingingArm;
        boolean renderMain = true, renderOff = true;
        boolean bowLike = mainHandItem.is(Items.BOW) || offHandItem.is(Items.BOW)
                || mainHandItem.is(Items.CROSSBOW) || offHandItem.is(Items.CROSSBOW);
        if (bowLike && target.isUsingItem()) {
            ItemStack used = target.getUseItem();
            if (used.is(Items.BOW) || used.is(Items.CROSSBOW)) {
                renderMain = target.getUsedItemHand() == InteractionHand.MAIN_HAND;
                renderOff = !renderMain;
            } else if (target.getUsedItemHand() == InteractionHand.MAIN_HAND
                    && offHandItem.is(Items.CROSSBOW) && CrossbowItem.isCharged(offHandItem)) {
                renderOff = false;
            }
        } else if (mainHandItem.is(Items.CROSSBOW) && CrossbowItem.isCharged(mainHandItem)) {
            renderOff = false;
        }
        if (renderMain) numen$renderArmWithItem(target, partialTick, pitch, InteractionHand.MAIN_HAND,
                swinging == InteractionHand.MAIN_HAND ? swing : 0F, mainHandItem,
                1F - Mth.lerp(partialTick, oMainHandHeight, mainHandHeight), pose, buffers, light);
        if (renderOff) numen$renderArmWithItem(target, partialTick, pitch, InteractionHand.OFF_HAND,
                swinging == InteractionHand.OFF_HAND ? swing : 0F, offHandItem,
                1F - Mth.lerp(partialTick, oOffHandHeight, offHandHeight), pose, buffers, light);
        buffers.endBatch();
    }

    @ModifyArg(method = {"renderPlayerArm", "renderMapHand"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;getRenderer(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/client/renderer/entity/EntityRenderer;"), index = 0)
    private Entity numen$armRenderer(Entity owner) {
        return SpectatorClient.target() == null ? owner : SpectatorClient.target();
    }

    @ModifyArg(method = {"renderPlayerArm", "renderMapHand"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/player/PlayerRenderer;renderRightHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;)V"), index = 3)
    private AbstractClientPlayer numen$rightArm(AbstractClientPlayer owner) {
        return SpectatorClient.target() == null ? owner : SpectatorClient.target();
    }

    @ModifyArg(method = {"renderPlayerArm", "renderMapHand"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/player/PlayerRenderer;renderLeftHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;)V"), index = 3)
    private AbstractClientPlayer numen$leftArm(AbstractClientPlayer owner) {
        return SpectatorClient.target() == null ? owner : SpectatorClient.target();
    }

    @Redirect(method = {"renderOneHandedMap", "renderTwoHandedMap"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;isInvisible()Z"))
    private boolean numen$mapArmVisible(LocalPlayer owner) {
        return SpectatorClient.target() == null ? owner.isInvisible() : SpectatorClient.target().isInvisible();
    }
}
