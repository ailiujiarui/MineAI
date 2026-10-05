package com.dwinovo.numen.community.bd;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.wintercogs.beyonddimensions.api.storage.handler.IStackHandler;
import com.wintercogs.beyonddimensions.api.storage.key.IStackKey;
import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import com.wintercogs.beyonddimensions.common.menu.DimensionsNetMenu;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读一个 Beyond Dimensions 终端的虚拟存储。
 *
 * <p>数据源是菜单本身的服务端字段 {@code DimensionsNetMenu.storage}:BD 把整张网的物品装进一个
 * {@link IStackHandler},不落进任何原版 {@code Slot},所以原版槽位转储只会看到空槽。这里直接问它要
 * {@link IStackHandler#getStorage()}(一组 {@code {key, amount}}),把每项翻译成 {@code {type, id, count}}。
 *
 * <p>物品键走 {@link ItemStackKey} 转物品注册名;流体/能量/气体等扩展键没有物品 id,就报它们的
 * 类型 id 与数量。条目多时截断到 {@value #MAX_ENTRIES} 条并如实报告,避免把整张网灌进模型上下文。
 *
 * <p>返回 {@code null} = 这个菜单不是 BD 终端,交给引擎回落通用转储。
 */
final class BdStorageRead {

    private static final int MAX_ENTRIES = 200;

    private BdStorageRead() {}

    static String read(NumenPlayer body, AbstractContainerMenu menu, String source) {
        if (!(menu instanceof DimensionsNetMenu terminal) || terminal.storage == null) {
            return null;
        }
        IStackHandler storage = terminal.storage;
        List<KeyAmount> stacks = storage.getStorage();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (KeyAmount stack : stacks) {
            if (entries.size() >= MAX_ENTRIES) {
                break;
            }
            IStackKey<?> key = stack.key();
            if (key == null || key.isEmpty()) {
                continue;
            }
            String id;
            if (key instanceof ItemStackKey itemKey) {
                ItemStack item = itemKey.getReadOnlyStack();
                id = BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
            } else {
                id = key.getTypeId().toString();
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", key.getTypeId().toString());
            row.put("id", id);
            row.put("count", stack.amount());
            entries.add(row);
        }

        DimensionsNet net = DimensionsNet.getPrimaryNetFromPlayer(body);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("network", net == null ? "" : net.getNetworkName().getString());
        data.put("slots_used", stacks.size());
        data.put("returned", entries.size());
        data.put("truncated", stacks.size() > entries.size());
        data.put("storage", entries);
        return TaskResult.ok("Read " + entries.size() + " of " + stacks.size()
                + " stacks from the Beyond Dimensions terminal; its storage is virtual, so the vanilla slot "
                + "dump was empty on purpose.", data).toJson();
    }
}
