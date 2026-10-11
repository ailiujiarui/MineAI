package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.combat.Menace;
import com.dwinovo.numen.core.combat.Swing;
import com.dwinovo.numen.core.task.combat.AttackTaskRecord;
import com.dwinovo.numen.core.task.combat.Fought;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskDispatch;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/** 战斗:走位带与点名攻击({@code attack})。 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class CombatGameTests {

    /** 战斗批次前置:普通难度(和平会把僵尸当场收走)+ 半夜(白天僵尸会被晒死)。 */
    @BeforeBatch(batch = "numen_combat")
    public static void prepareCombatBatch(ServerLevel level) {
        settleWorld(level, Difficulty.NORMAL, MIDNIGHT);
    }

    /**
     * 走位带:她该稳在「它够不着我」与「我够得着它」之间,而且真的能砍到。
     *
     * <p>盯的是两个反复出错的地方:外沿一旦超过她的够到距离,她会停在打不到的位置站着挨打
     * (实测在 4.0~4.8 之间摆、有效血量 8 掉到 5);内沿一旦叠上格量化补偿,带宽从 1.28 压到
     * 0.57,格分辨率装不下,寻路一路失败,她停在边缘不动。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_combat")
    public static void combat_holds_the_skirmish_band(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = armedCompanion(helper, new BlockPos(3, 2, 3));
        Zombie zombie = spawnZombie(helper, new BlockPos(11, 2, 11), companion);

        double inner = Menace.rawDangerRadius(zombie, companion);
        double outer = Swing.reachTo(
                companion.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE),
                zombie.getBbWidth());
        float startHealth = zombie.getHealth();

        int[] insideBand = {0};
        succeedWhen(helper, () -> {
            helper.assertTrue(companion.isAlive(), "companion died to a single zombie");
            double d = companion.distanceTo(zombie);
            if (d >= inner && d <= outer) {
                insideBand[0]++;
            }
            // 砍掉血就说明外沿确实在够得着的范围内 —— 站在打不到的地方是这条最先抓的病。
            helper.assertTrue(zombie.getHealth() < startHealth || !zombie.isAlive()
                            || insideBand[0] < 200,
                    "companion never landed a hit: distance " + String.format("%.2f", d)
                            + " band [" + String.format("%.2f", inner) + ", "
                            + String.format("%.2f", outer) + "]");
            // 倒下、而且最后伤它的是她:被收走或被别的东西弄死的僵尸也"不在了",那不算她打赢
            helper.assertTrue(zombie.isDeadOrDying() && zombie.getLastHurtByMob() == companion,
                    "zombie still up, or it did not fall to her");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 够得比她还远的怪(原版里是十四号往上的史莱姆:它的击打范围随身宽按对角放大,她的够到距离只加半个身宽):打得着
     * 又挨不着的那条带不存在,走位环的内沿归零,她照样走进够得着的地方砍它,而不是在编走位目标时出错收场。史莱姆不动
     * 不还手,测的只是她走不走过去打。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_combat")
    public static void attack_walks_in_on_a_creature_that_outreaches_her(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = armedCompanion(helper, new BlockPos(2, 2, 2));
        var slime = EntityType.SLIME.create(level);
        helper.assertTrue(slime != null, "slime did not spawn");
        slime.setSize(14, true);
        BlockPos at = helper.absolutePos(new BlockPos(11, 2, 11));
        slime.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        slime.setNoAi(true);
        level.addFreshEntity(slime);
        helper.assertTrue(Menace.rawDangerRadius(slime, companion) >= Swing.reachTo(
                        companion.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE), slime.getBbWidth()),
                "this slime does not outreach her, the scene tests nothing");
        float startHealth = slime.getHealth();
        TaskRecord record = lua(companion, "numen.fight.attack(" + slime.getId() + ")").task();

        succeedWhen(helper, () -> {
            helper.assertTrue(record.getResult() == null || !record.getResult().message().contains("internal error"),
                    "the attack broke down: " + record.getResult());
            helper.assertTrue(slime.getHealth() < startHealth && slime.getLastHurtByMob() == companion,
                    "she never walked in to hit the slime");
            slime.discard();
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 目标站在它那一格的远边上:她那一格离它那一格三格整,可她眼睛到它碰撞箱有三格多,手够不着。走位说到了、出手说够不着,
     * 她就站在原地一刀不挥,直到任务超时;走位与出手问的是同一个"够得着",她就会再往前走一格去打。僵尸不动不还手,测的只是
     * 她走不走进够得着的地方。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_combat")
    public static void attack_steps_in_on_a_target_at_the_far_edge_of_its_cell(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = armedCompanion(helper, new BlockPos(5, 2, 8));
        Zombie zombie = EntityType.ZOMBIE.create(level);
        helper.assertTrue(zombie != null, "zombie did not spawn");
        BlockPos at = helper.absolutePos(new BlockPos(8, 2, 8));
        zombie.moveTo(at.getX() + 0.95, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        zombie.setNoAi(true);
        level.addFreshEntity(zombie);
        float startHealth = zombie.getHealth();
        ToolRun attack = lua(companion, "numen.fight.attack(" + zombie.getId() + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(attack.task() != null, "the attack was not accepted: " + attack.reply());
            helper.assertTrue(zombie.getHealth() < startHealth && zombie.getLastHurtByMob() == companion,
                    "she stood " + String.format("%.2f", companion.distanceTo(zombie))
                            + " away and never hit the zombie: " + attack.task().getResult());
            zombie.discard();
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 要打的那只一刻不停地在原地晃(真的怪围着主人打时就是这样):走位的目标跟着它每刻都变,从她脚下派出去的那次搜索得搜完,
     * 不能每变一次就作废重派——那样一次也搜不完,她站在远处一步不动。
     */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_combat")
    public static void attack_closes_in_on_a_target_that_never_stands_still(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = armedCompanion(helper, new BlockPos(2, 2, 8));
        Zombie zombie = EntityType.ZOMBIE.create(level);
        helper.assertTrue(zombie != null, "zombie did not spawn");
        BlockPos at = helper.absolutePos(new BlockPos(12, 2, 8));
        zombie.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        zombie.setNoAi(true);
        level.addFreshEntity(zombie);
        float startHealth = zombie.getHealth();
        // 每刻在它那一格里挪一点,不出格
        helper.onEachTick(() -> zombie.setPos(at.getX() + 0.5 + 0.2 * Math.sin(level.getGameTime() * 0.7),
                zombie.getY(), at.getZ() + 0.5 + 0.2 * Math.cos(level.getGameTime() * 0.7)));
        ToolRun attack = lua(companion, "numen.fight.attack(" + zombie.getId() + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(attack.task() != null, "the attack was not accepted: " + attack.reply());
            helper.assertTrue(zombie.getHealth() < startHealth && zombie.getLastHurtByMob() == companion,
                    "she is still " + String.format("%.2f", companion.distanceTo(zombie))
                            + " away and never hit the jittering zombie: " + attack.task().getResult());
            zombie.discard();
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 主人在线、却离她很远时,要打的那只站在隔壁区块里:她自己那块加载垫得让伸手所及的邻区块也在跑实体刻,否则那只是冻住的——挨了
     * 第一下之后受击无敌帧永远不退,她一直等着出第二刀,它也打不死。GameTest 的场地区块是钉住的,这里搬到两千格外、高处
     * 一块没人钉的地方现搭一条石台,让她的加载垫成为那里唯一的票据。僵尸不动,测的只是她能不能把它打死。
     */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_combat")
    public static void attack_finishes_a_target_across_a_chunk_border_far_from_players(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 她站在区块 cx 的东边沿上,僵尸站在隔壁区块 cx + 1 的西边沿上
        BlockPos near = helper.absolutePos(BlockPos.ZERO);
        int cx = (near.getX() >> 4) + 128;
        int cz = near.getZ() >> 4;
        int y = 200;
        int z = cz * 16 + 7;
        level.getChunk(cx, cz);
        level.getChunk(cx + 1, cz);
        for (int x = cx * 16 + 10; x <= cx * 16 + 20; x++) {
            level.setBlockAndUpdate(new BlockPos(x, y - 1, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        }
        // 加载垫只在主人在线时续:主人站在场地里(GameTest 没有真玩家,拿另一个同伴的身份顶上,它自己不带区块)
        BlockPos home = helper.absolutePos(new BlockPos(2, 2, 2));
        NumenPlayer owner = CompanionFactory.spawn(level.getServer(), java.util.UUID.randomUUID(),
                "gametest_far_owner", java.util.UUID.randomUUID(), level,
                new net.minecraft.world.phys.Vec3(home.getX() + 0.5, home.getY(), home.getZ() + 0.5));
        NumenPlayer companion = CompanionFactory.spawn(level.getServer(), java.util.UUID.randomUUID(),
                "gametest_far_fighter", owner.getUUID(), level,
                new net.minecraft.world.phys.Vec3(cx * 16 + 13.5, y, z + 0.5));
        companion.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD));
        Zombie zombie = EntityType.ZOMBIE.create(level);
        helper.assertTrue(zombie != null, "zombie did not spawn");
        ToolRun[] attack = new ToolRun[1];

        // 等她的加载垫把两块区块都立起来,放下僵尸;世界里找得到它了再下命令
        steps(helper)
                .thenIdle(40)
                .thenExecute(() -> {
                    zombie.moveTo(cx * 16 + 16.5, y, z + 0.5, 0.0f, 0.0f);
                    zombie.setNoAi(true);
                    level.addFreshEntity(zombie);
                })
                .thenWaitUntil(() -> helper.assertTrue(level.getEntity(zombie.getId()) == zombie,
                        "the zombie never showed up in the world; she is at " + companion.blockPosition()
                                + " in chunk " + companion.chunkPosition()))
                .thenExecute(() -> attack[0] = lua(companion, "numen.fight.attack(" + zombie.getId() + ")"))
                .thenWaitUntil(() -> {
                    helper.assertTrue(attack[0].task() != null, "the attack was not accepted: " + attack[0].reply());
                    helper.assertTrue(zombie.isDeadOrDying() && zombie.getLastHurtByMob() == companion,
                            "the zombie across the chunk border is still up (health " + zombie.getHealth()
                                    + ", hurt time " + zombie.hurtTime + "): " + attack[0].task().getResult());
                })
                .thenExecute(() -> {
                    zombie.discard();
                    CompanionFactory.despawn(level.getServer(), companion);
                    CompanionFactory.despawn(level.getServer(), owner);
                })
                .thenSucceed();
    }

    /**
     * 点名打不敌对的东西:一头猪,附近一只怪都没有。她必须走过去把它打掉——走位目标由
     * "有没有目标"决定,不由"附近有没有怪"决定;后者只是躲避场。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_combat")
    public static void attack_hunts_a_named_passive_target(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = armedCompanion(helper, new BlockPos(2, 2, 2));
        var pig = EntityType.PIG.create(level);
        helper.assertTrue(pig != null, "pig did not spawn");
        BlockPos at = helper.absolutePos(new BlockPos(13, 2, 13));
        pig.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        pig.setNoAi(true);   // 站着别跑,这条测的是她走不走过去,不是追逐
        level.addFreshEntity(pig);
        TaskRecord record = lua(companion, "numen.fight.attack(" + pig.getId() + ")").task();

        succeedWhen(helper, () -> {
            helper.assertTrue(pig.isDeadOrDying() && pig.getLastHurtByMob() == companion,
                    "the pig is still alive — she never walked over to hit it");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 点名攻击落盘重放的那一行写的是目标的 UUID:运行期编号重启后会发给别的东西,照旧号重放可能打到毫不相干的一只。
     * 受理时找不到的编号不写进去(任务照旧记它丢失);一只都找不到就当场失败,不留一行会变成不点名清场的重放。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_combat")
    public static void attack_is_replayed_by_uuid_not_by_runtime_id(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var pig = EntityType.PIG.create(level);
        helper.assertTrue(pig != null, "pig did not spawn");
        BlockPos at = helper.absolutePos(new BlockPos(13, 2, 13));
        pig.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        pig.setNoAi(true);
        level.addFreshEntity(pig);
        BlockPos spawn = helper.absolutePos(new BlockPos(2, 2, 2));
        NumenPlayer companion = com.dwinovo.numen.entity.Companions.summon(server, java.util.UUID.randomUUID(),
                "gametest_uuid_hunter", level, new net.minecraft.world.phys.Vec3(spawn.getX() + 0.5, spawn.getY(),
                        spawn.getZ() + 0.5));
        ToolRun nobody = lua(companion, "numen.fight.attack(999998)");
        ToolRun attack = lua(companion, "numen.fight.attack(" + pig.getId() + ")");
        String recorded = com.dwinovo.numen.entity.CompanionRegistry.get(server).find(companion.getUUID()).taskLua();

        succeedWhen(helper, () -> {
            helper.assertTrue(nobody.done() && !nobody.succeeded() && nobody.outcome().contains("999998"),
                    "naming only missing entities did not fail on the spot: " + nobody.reply());
            helper.assertTrue(attack.task() != null, "the attack was not accepted: " + attack.reply());
            helper.assertTrue(recorded.equals("numen.fight.attack(\"" + pig.getUUID() + "\")"),
                    "the replay recipe does not name exactly the pig by its UUID: " + recorded);
            com.dwinovo.numen.entity.Companions.dismiss(server, companion);
            pig.discard();
        });
    }

    /** 没问到同意的目标仍在:脚本得到 needs_consent 和零出手的实际账,不能说成目标丢了。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_combat")
    public static void an_offline_attack_reports_unanswered_consent_and_zero_hits(GameTestHelper helper) {
        NumenPlayer companion = armedCompanion(helper, new BlockPos(3, 2, 4));
        var villager = helper.spawn(EntityType.VILLAGER, new BlockPos(7, 2, 4));
        villager.setNoAi(true);
        ToolRun attack = lua(companion, "numen.fight.attack(" + villager.getId() + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(attack.done(), "the attack has not ended");
            var result = attack.task().getResult();
            helper.assertTrue(!result.success()
                            && result.kind() == com.dwinovo.numen.agent.script.ErrorKind.NEEDS_CONSENT,
                    "unanswered consent was not reported to the script: " + result);
            helper.assertTrue(result.message().contains("owner could not be reached")
                            && result.message().contains(com.dwinovo.numen.permission.ConsentDesk.OWNER_ABSENT)
                            && !result.message().contains("refused by the owner"),
                    "the owner's absence was lost or turned into a refusal: " + result);
            Fought fought = attack.result(Fought.class);
            helper.assertTrue(fought.strikes() == 0 && fought.fought().size() == 1
                            && fought.fought().getFirst().id() == villager.getId()
                            && fought.fought().getFirst().status().equals("pending")
                            && fought.fought().getFirst().strikes() == 0,
                    "the unanswered target or its zero hits were lost: " + fought);
            helper.assertTrue(villager.isAlive() && villager.getHealth() == villager.getMaxHealth(),
                    "the villager was hurt without consent");
            villager.discard();
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 真正打倒了允许打的目标,未获同意与不存在的目标仍分别记账,不能把部分完成说成全部打完。 */
    @GameTest(template = "floor16", timeoutTicks = 2000, batch = "numen_combat")
    public static void a_partial_attack_reports_unanswered_and_lost_targets(GameTestHelper helper) {
        NumenPlayer companion = armedCompanion(helper, new BlockPos(3, 2, 4));
        var pig = helper.spawn(EntityType.PIG, new BlockPos(5, 2, 4));
        pig.setNoAi(true);
        pig.setHealth(1);
        var villager = helper.spawn(EntityType.VILLAGER, new BlockPos(9, 2, 4));
        villager.setNoAi(true);
        var record = new AttackTaskRecord("attack", "partial-consent", helper.getLevel().getGameTime() + 2000,
                List.of(pig.getId(), villager.getId(), -12345678), false);
        TaskDispatch.setTask(companion, record);

        succeedWhen(helper, () -> {
            var result = record.getResult();
            helper.assertTrue(result != null, "the mixed attack has not ended");
            helper.assertTrue(result.success() && pig.isDeadOrDying() && pig.getLastHurtByMob() == companion,
                    "the permitted pig did not really fall to her: " + result);
            helper.assertTrue(result.message().contains("defeated 1/3")
                            && result.message().contains("left for later consent")
                            && result.message().contains(com.dwinovo.numen.permission.ConsentDesk.OWNER_ABSENT),
                    "partial completion concealed the unanswered consent: " + result);
            Fought fought = (Fought) result.value();
            helper.assertTrue(fought.fought().size() == 3 && fought.strikes() > 0
                            && fought.fought().get(0).status().equals("defeated")
                            && fought.fought().get(1).status().equals("pending")
                            && fought.fought().get(1).strikes() == 0
                            && fought.fought().get(2).status().equals("lost")
                            && com.dwinovo.numen.permission.ConsentDesk.OWNER_ABSENT.equals(
                                    record.pending().get(villager.getId())),
                    "the partial attack lost the per-target account: " + fought);
            helper.assertTrue(villager.isAlive() && villager.getHealth() == villager.getMaxHealth(),
                    "the unanswered villager was attacked");
            pig.discard();
            villager.discard();
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 明确拒绝与未答复混在同一份名单里:拒绝仍是 denied,实际账保留另一只的 pending 和丢失的编号。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_combat")
    public static void a_refused_attack_keeps_its_unanswered_and_lost_targets(GameTestHelper helper) {
        NumenPlayer companion = armedCompanion(helper, new BlockPos(3, 2, 4));
        var pig = helper.spawn(EntityType.PIG, new BlockPos(5, 2, 4));
        pig.setNoAi(true);
        var villager = helper.spawn(EntityType.VILLAGER, new BlockPos(9, 2, 4));
        villager.setNoAi(true);
        com.dwinovo.numen.permission.PermissionStore.of(helper.getLevel().getServer(), companion.getOwnerUuid())
                .add(com.dwinovo.numen.permission.Verdict.Kind.DENY,
                        com.dwinovo.numen.permission.Rule.parse("attack(entity:" + pig.getUUID() + ")"));
        var record = new AttackTaskRecord("attack", "refused-consent", helper.getLevel().getGameTime() + 200,
                List.of(pig.getId(), villager.getId(), -12345678), false);
        TaskDispatch.setTask(companion, record);

        succeedWhen(helper, () -> {
            var result = record.getResult();
            helper.assertTrue(result != null, "the mixed refusal has not ended");
            helper.assertTrue(!result.success()
                            && result.kind() == com.dwinovo.numen.agent.script.ErrorKind.DENIED
                            && result.message().contains("denied by rule")
                            && result.message().contains("owner could not be reached"),
                    "a refusal was made retryable or the unanswered target was concealed: " + result);
            Fought fought = (Fought) result.value();
            helper.assertTrue(fought.strikes() == 0 && fought.fought().size() == 3
                            && fought.fought().get(0).status().startsWith("refused: denied by rule")
                            && fought.fought().get(1).status().equals("pending")
                            && fought.fought().get(2).status().equals("lost"),
                    "the mixed refusal has the wrong per-target account: " + fought);
            helper.assertTrue(pig.getHealth() == pig.getMaxHealth()
                            && villager.getHealth() == villager.getMaxHealth(),
                    "an unauthorized target was attacked");
            pig.discard();
            villager.discard();
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 搁下征询之后目标真的离场:收尾交代丢失,旧的未获同意理由不能盖住新的世界事实。 */
    @GameTest(template = "floor16", timeoutTicks = 2000, batch = "numen_combat")
    public static void an_unanswered_target_that_leaves_is_reported_as_lost(GameTestHelper helper) {
        NumenPlayer companion = armedCompanion(helper, new BlockPos(3, 2, 4));
        var pig = helper.spawn(EntityType.PIG, new BlockPos(12, 2, 4));
        pig.setNoAi(true);
        pig.setHealth(1);
        var villager = helper.spawn(EntityType.VILLAGER, new BlockPos(7, 2, 4));
        villager.setNoAi(true);
        var record = new AttackTaskRecord("attack", "lost-consent", helper.getLevel().getGameTime() + 2000,
                List.of(pig.getId(), villager.getId()), false);
        TaskDispatch.setTask(companion, record);

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(record.pending().containsKey(villager.getId()),
                        "the villager has not been left for a later consent"))
                .thenExecute(villager::discard)
                .thenWaitUntil(() -> helper.assertTrue(record.getResult() != null, "the attack has not ended"))
                .thenExecute(() -> {
                    var result = record.getResult();
                    Fought fought = (Fought) result.value();
                    helper.assertTrue(result.success() && pig.isDeadOrDying() && pig.getLastHurtByMob() == companion,
                            "the permitted pig did not really fall to her: " + result);
                    helper.assertTrue(record.pending().isEmpty() && record.lost().contains(villager.getId())
                                    && fought.fought().get(1).status().equals("lost")
                                    && fought.fought().get(1).strikes() == 0
                                    && !result.message().contains("left for later consent"),
                            "the absent target retained stale consent state: " + result);
                    pig.discard();
                    CompanionFactory.despawn(helper.getLevel().getServer(), companion);
                })
                .thenSucceed();
    }

    /** 主人回场不让旧活反复征询;下一次正式调用重新问,点头之后才打。 */
    @GameTest(template = "floor16", timeoutTicks = 2000, batch = "numen_combat")
    public static void an_unanswered_attack_is_retried_only_by_a_new_call(GameTestHelper helper) {
        NumenPlayer companion = armedCompanion(helper, new BlockPos(3, 2, 4));
        var pig = helper.spawn(EntityType.PIG, new BlockPos(12, 2, 4));
        pig.setNoAi(true);
        pig.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40);
        pig.setHealth(40);
        var villager = helper.spawn(EntityType.VILLAGER, new BlockPos(7, 2, 4));
        villager.setNoAi(true);
        var record = new AttackTaskRecord("attack", "later-consent", helper.getLevel().getGameTime() + 2000,
                List.of(pig.getId(), villager.getId()), false);
        TaskDispatch.setTask(companion, record);
        var owner = new net.minecraft.server.level.ServerPlayer[1];
        var retry = new ToolRun[1];

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(record.pending().containsKey(villager.getId()),
                        "the villager has not been left for a later consent"))
                .thenExecute(() -> owner[0] = presentOwner(helper, companion, "gametest_later_attack_owner"))
                .thenIdle(10)
                .thenExecute(() -> {
                    helper.assertTrue(com.dwinovo.numen.permission.ConsentDesk.of(companion).pending() == null,
                            "the same unanswered attack was asked again in the same task");
                    helper.assertTrue(villager.getHealth() == villager.getMaxHealth(),
                            "the villager was hurt before a new authorization");
                })
                .thenWaitUntil(() -> helper.assertTrue(record.getResult() != null, "the attack has not ended"))
                .thenExecute(() -> {
                    var result = record.getResult();
                    Fought fought = (Fought) result.value();
                    helper.assertTrue(result.success() && pig.getLastHurtByMob() == companion
                                    && fought.fought().get(0).status().equals("defeated")
                                    && fought.fought().get(1).status().equals("pending")
                                    && fought.fought().get(1).strikes() == 0
                                    && villager.getHealth() == villager.getMaxHealth(),
                            "the old attack revisited its unanswered target: " + result);
                    retry[0] = lua(companion, "numen.fight.attack(" + villager.getId() + ")");
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        com.dwinovo.numen.permission.ConsentDesk.of(companion).pending() != null,
                        "the new attack did not ask the present owner"))
                .thenExecute(() -> {
                    helper.assertTrue(villager.getHealth() == villager.getMaxHealth(),
                            "the new attack hit before consent");
                    var desk = com.dwinovo.numen.permission.ConsentDesk.of(companion);
                    desk.answer(desk.pending().id(), com.dwinovo.numen.permission.ConsentAnswer.Decision.ALLOW_ONCE, "");
                })
                .thenWaitUntil(() -> helper.assertTrue(retry[0].done(), "the retried attack has not ended"))
                .thenExecute(() -> {
                    helper.assertTrue(retry[0].succeeded() && villager.isDeadOrDying()
                                    && villager.getLastHurtByMob() == companion
                                    && retry[0].result(Fought.class).fought().getFirst().status().equals("defeated"),
                            "the new attack did not run after actual consent: " + retry[0].outcome());
                    pig.discard();
                    villager.discard();
                    CompanionFactory.despawn(helper.getLevel().getServer(), companion);
                    leave(owner[0]);
                })
                .thenSucceed();
    }

    /** 无差别战斗里还追着她的目标没获同意:零出手按 needs_consent 收尾,不能报成功或已清场。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_combat")
    public static void an_unanswered_hostile_does_not_count_as_a_cleared_fight(GameTestHelper helper) {
        NumenPlayer companion = armedCompanion(helper, new BlockPos(3, 2, 4));
        Zombie zombie = still(helper, new BlockPos(7, 2, 4));
        zombie.setCustomName(net.minecraft.network.chat.Component.literal("gametest_unanswered_foe"));
        zombie.setTarget(companion);
        var record = new AttackTaskRecord("attack", "uncleared-consent", helper.getLevel().getGameTime() + 200,
                List.of(), true);
        TaskDispatch.setTask(companion, record);

        succeedWhen(helper, () -> {
            var result = record.getResult();
            helper.assertTrue(result != null, "the unanswered fight has not ended");
            helper.assertTrue(!result.success()
                            && result.kind() == com.dwinovo.numen.agent.script.ErrorKind.NEEDS_CONSENT
                            && result.message().contains("owner could not be reached")
                            && !result.message().contains("nothing is coming after you any more"),
                    "an unanswered threat was reported as a cleared fight: " + result);
            Fought fought = (Fought) result.value();
            helper.assertTrue(zombie.isAlive() && zombie.getTarget() == companion
                            && zombie.getHealth() == zombie.getMaxHealth()
                            && fought.strikes() == 0 && fought.fought().size() == 1
                            && fought.fought().getFirst().id() == zombie.getId()
                            && fought.fought().getFirst().status().equals("pending"),
                    "the unconsented threat was hit or disappeared from the account: " + fought);
            zombie.discard();
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 无差别战斗真的打倒一只,另一只仍追她但没获同意:说清已停手与部分战果,不能报威胁已经没了。 */
    @GameTest(template = "floor16", timeoutTicks = 2000, batch = "numen_combat")
    public static void a_partial_fight_keeps_the_unanswered_threat_in_its_account(GameTestHelper helper) {
        NumenPlayer companion = armedCompanion(helper, new BlockPos(3, 2, 4));
        Zombie allowed = still(helper, new BlockPos(5, 2, 4));
        allowed.setTarget(companion);
        allowed.setHealth(1);
        Zombie unanswered = still(helper, new BlockPos(9, 2, 4));
        unanswered.setCustomName(net.minecraft.network.chat.Component.literal("gametest_pending_threat"));
        unanswered.setTarget(companion);
        var record = new AttackTaskRecord("attack", "partial-threat", helper.getLevel().getGameTime() + 2000,
                List.of(), true);
        TaskDispatch.setTask(companion, record);

        succeedWhen(helper, () -> {
            var result = record.getResult();
            helper.assertTrue(result != null, "the partial fight has not ended");
            Fought fought = (Fought) result.value();
            helper.assertTrue(result.success() && allowed.isDeadOrDying() && allowed.getLastHurtByMob() == companion
                            && result.message().contains("stopped fighting after defeating 1 hostiles")
                            && result.message().contains("left for later consent")
                            && !result.message().contains("nothing is coming after you any more"),
                    "the partial fight was reported as clearing every threat: " + result);
            helper.assertTrue(unanswered.isAlive() && unanswered.getTarget() == companion
                            && unanswered.getHealth() == unanswered.getMaxHealth()
                            && fought.fought().stream().anyMatch(f -> f.id() == unanswered.getId()
                                    && f.status().equals("pending") && f.strikes() == 0),
                    "the unanswered threat was hit or lost from the partial account: " + fought);
            allowed.discard();
            unanswered.discard();
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 无差别战斗的未获同意目标随后离场:经手账仍会看到它,把 pending 清成 lost。 */
    @GameTest(template = "floor16", timeoutTicks = 2000, batch = "numen_combat")
    public static void an_unanswered_hostile_that_leaves_is_settled_in_a_fight(GameTestHelper helper) {
        NumenPlayer companion = armedCompanion(helper, new BlockPos(3, 2, 4));
        Zombie allowed = still(helper, new BlockPos(12, 2, 4));
        allowed.setTarget(companion);
        allowed.setHealth(1);
        Zombie unanswered = still(helper, new BlockPos(7, 2, 4));
        unanswered.setCustomName(net.minecraft.network.chat.Component.literal("gametest_departing_threat"));
        unanswered.setTarget(companion);
        var record = new AttackTaskRecord("attack", "departing-threat", helper.getLevel().getGameTime() + 2000,
                List.of(), true);
        TaskDispatch.setTask(companion, record);

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(record.pending().containsKey(unanswered.getId()),
                        "the named threat has not been left for a later consent"))
                .thenExecute(unanswered::discard)
                .thenWaitUntil(() -> helper.assertTrue(record.getResult() != null, "the fight has not ended"))
                .thenExecute(() -> {
                    var result = record.getResult();
                    Fought fought = (Fought) result.value();
                    helper.assertTrue(result.success() && allowed.isDeadOrDying()
                                    && allowed.getLastHurtByMob() == companion,
                            "the permitted threat did not really fall to her: " + result);
                    helper.assertTrue(record.pending().isEmpty() && record.lost().contains(unanswered.getId())
                                    && fought.fought().stream().anyMatch(f -> f.id() == unanswered.getId()
                                            && f.status().equals("lost") && f.strikes() == 0)
                                    && !result.message().contains("left for later consent"),
                            "the departed threat retained stale consent state: " + result);
                    allowed.discard();
                    CompanionFactory.despawn(helper.getLevel().getServer(), companion);
                })
                .thenSucceed();
    }

    /** 同一战斗口区分明确不许打与真的没有目标:前者 denied、零出手,后者才是空场成功。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_combat")
    public static void a_refused_fight_is_denied_while_an_empty_fight_succeeds(GameTestHelper helper) {
        NumenPlayer companion = armedCompanion(helper, new BlockPos(3, 2, 4));
        Zombie zombie = still(helper, new BlockPos(7, 2, 4));
        zombie.setTarget(companion);
        com.dwinovo.numen.permission.PermissionStore.of(helper.getLevel().getServer(), companion.getOwnerUuid())
                .add(com.dwinovo.numen.permission.Verdict.Kind.DENY,
                        com.dwinovo.numen.permission.Rule.parse("attack(entity:" + zombie.getUUID() + ")"));
        var refused = new AttackTaskRecord("attack", "refused-fight", helper.getLevel().getGameTime() + 200,
                List.of(), true);
        var empty = new AttackTaskRecord("attack", "empty-fight", helper.getLevel().getGameTime() + 200,
                List.of(), true);
        TaskDispatch.setTask(companion, refused);

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(refused.getResult() != null, "the refused fight has not ended"))
                .thenExecute(() -> {
                    var result = refused.getResult();
                    helper.assertTrue(!result.success()
                                    && result.kind() == com.dwinovo.numen.agent.script.ErrorKind.DENIED
                                    && result.message().contains("denied by rule")
                                    && ((Fought) result.value()).strikes() == 0
                                    && zombie.getHealth() == zombie.getMaxHealth(),
                            "a forbidden threat was attacked or treated as a cleared fight: " + result);
                    zombie.discard();
                    TaskDispatch.setTask(companion, empty);
                })
                .thenWaitUntil(() -> helper.assertTrue(empty.getResult() != null, "the empty fight has not ended"))
                .thenExecute(() -> {
                    var result = empty.getResult();
                    helper.assertTrue(result.success() && ((Fought) result.value()).strikes() == 0,
                            "a genuinely empty fight did not retain its normal success: " + result);
                    CompanionFactory.despawn(helper.getLevel().getServer(), companion);
                })
                .thenSucceed();
    }

    /** 已搁下的征询理由随超时或取消一起交账,终态仍各自是 timeout / interrupted。 */
    @GameTest(template = "floor16", timeoutTicks = 40, batch = "numen_combat")
    public static void an_unanswered_target_stays_in_timeout_and_cancelled_fight_receipts(GameTestHelper helper) {
        for (var ending : List.of(com.dwinovo.numen.task.TaskState.TIMEOUT, com.dwinovo.numen.task.TaskState.CANCELLED)) {
            NumenPlayer companion = armedCompanion(helper, new BlockPos(3, 2, 4));
            Zombie allowed = still(helper, new BlockPos(12, 2, 4));
            allowed.setTarget(companion);
            Zombie unanswered = still(helper, new BlockPos(7, 2, 4));
            unanswered.setCustomName(net.minecraft.network.chat.Component.literal("gametest_ended_threat"));
            unanswered.setTarget(companion);
            var record = new AttackTaskRecord("attack", "ended-threat", helper.getLevel().getGameTime() + 200,
                    List.of(), true);
            var attack = new com.dwinovo.numen.core.task.combat.AttackCompanionTask(companion, record);
            attack.start(companion);
            helper.assertTrue(attack.tick(companion) == com.dwinovo.numen.task.TaskState.RUNNING
                            && record.pending().containsKey(unanswered.getId()),
                    "the fight has no real unanswered target before ending");
            var result = attack.result(ending);
            helper.assertTrue((ending == com.dwinovo.numen.task.TaskState.TIMEOUT ? result.timedOut() : result.interrupted())
                            && result.message().contains("left for later consent")
                            && result.message().contains(com.dwinovo.numen.permission.ConsentDesk.OWNER_ABSENT)
                            && ((Fought) result.value()).fought().stream().anyMatch(f -> f.id() == unanswered.getId()
                                    && f.status().equals("pending") && f.strikes() == 0)
                            && unanswered.getHealth() == unanswered.getMaxHealth(),
                    "ending the fight lost its unanswered consent or changed the terminal kind: " + result);
            allowed.discard();
            unanswered.discard();
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        }
        helper.succeed();
    }

    /**
     * 库里的 {@code numen.fight.clear}:四周两只僵尸,一次 {@code numen.fight.attack} 打一只,打到范围里一只不剩,返回打了几场。两只都倒在
     * 她手下,一场一个 numen.fight.attack。半径给小,不扫到隔壁场地。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_combat")
    public static void fight_clear_fights_every_hostile_around_her_one_at_a_time(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = armedCompanion(helper, new BlockPos(8, 2, 8));
        List<Zombie> zombies = List.of(still(helper, new BlockPos(4, 2, 8)), still(helper, new BlockPos(12, 2, 9)));
        ToolRun clear = lua(companion, "return numen.fight.clear(7)");

        succeedWhen(helper, () -> {
            helper.assertTrue(clear.receipt() != null, "numen.fight.clear has not finished");
            helper.assertTrue(clear.ranToTheEnd() && clear.receipt().contains("\\nreturned: 2"),
                    "numen.fight.clear did not end after two fights: " + clear.receipt());
            helper.assertTrue(clear.tasks("numen.fight.attack").size() == 2,
                    "not one numen.fight.attack per zombie: " + clear.receipt());
            for (Zombie zombie : zombies) {
                helper.assertTrue(zombie.isDeadOrDying() && zombie.getLastHurtByMob() == companion,
                        "a zombie is still up, or it did not fall to her");
            }
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 打不过就跑归逃跑本能,跑不掉就就地还手:她只剩四颗心、手里有剑,一只僵尸追着她,脚下是悬在半空的一块 5×5 石台——四下
     * 三十二格内没有能站的落点。逃跑本能先接过身体,找不到落点就让出来,留下一条说"没有退路"的本能事件;自卫接着打,僵尸挨了她的剑。
     * 石台搬到两千格外、高处一块没人钉的地方,和隔壁场地不沾边,所以四下确实无处可去;那一块区块钉住让实体照常走刻。主人不在线,
     * 本能事件进出箱,读得到。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_combat")
    public static void an_outmatched_body_with_no_way_out_fights_back(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos near = helper.absolutePos(BlockPos.ZERO);
        int cx = (near.getX() >> 4) + 160;
        int cz = near.getZ() >> 4;
        int y = 200;
        int x0 = cx * 16 + 6;
        int z0 = cz * 16 + 6;
        level.setChunkForced(cx, cz, true);
        for (int x = x0; x < x0 + 5; x++) {
            for (int z = z0; z < z0 + 5; z++) {
                level.setBlockAndUpdate(new BlockPos(x, y - 1, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            }
        }
        NumenPlayer companion = CompanionFactory.spawn(level.getServer(), java.util.UUID.randomUUID(),
                "gametest_cornered", java.util.UUID.randomUUID(), level,
                new net.minecraft.world.phys.Vec3(x0 + 3.5, y, z0 + 2.5));
        companion.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD));
        // 低于 18 停止自然回血、仍可疾跑:僵尸入场后保留低血、扛不住的前提。
        companion.getFoodData().setFoodLevel(17);
        Zombie zombie = EntityType.ZOMBIE.create(level);
        helper.assertTrue(zombie != null, "zombie did not spawn");
        var outbox = com.dwinovo.numen.entity.EventOutbox.get(level.getServer());

        steps(helper)
                .thenIdle(40)
                .thenExecute(() -> {
                    // 进世界时血是满的:站稳了再压到四颗心,僵尸这才来
                    companion.setHealth(8.0f);
                    helper.assertTrue(Menace.outmatched(companion), "the cornered fixture can still hold its own");
                    zombie.moveTo(x0 + 0.5, y, z0 + 2.5, 0.0f, 0.0f);
                    zombie.setTarget(companion);
                    level.addFreshEntity(zombie);
                })
                .thenWaitUntil(() -> {
                    var flee = outbox.peek(companion.getUUID()).entries().stream()
                            .filter(e -> e.type().equals(com.dwinovo.numen.agent.inbox.EventTypes.REFLEX)
                                    && e.text().contains("reflex=\"flee\""))
                            .toList();
                    helper.assertTrue(!flee.isEmpty() && flee.get(0).text().contains("no way out"),
                            "the flee instinct did not report that there was nowhere to run: "
                                    + outbox.peek(companion.getUUID()).entries());
                    helper.assertTrue(zombie.getLastHurtByMob() == companion,
                            "cornered, she did not fight back (zombie health " + zombie.getHealth() + ")");
                })
                .thenExecute(() -> {
                    outbox.forget(companion.getUUID());
                    zombie.discard();
                    CompanionFactory.despawn(level.getServer(), companion);
                    level.setChunkForced(cx, cz, false);
                })
                .thenSucceed();
    }

    /** 一只不动不还手的僵尸。 */
    private static Zombie still(GameTestHelper helper, BlockPos rel) {
        Zombie zombie = EntityType.ZOMBIE.create(helper.getLevel());
        helper.assertTrue(zombie != null, "zombie did not spawn");
        BlockPos at = helper.absolutePos(rel);
        zombie.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        zombie.setNoAi(true);
        helper.getLevel().addFreshEntity(zombie);
        return zombie;
    }

    private static Zombie spawnZombie(GameTestHelper helper, BlockPos rel, NumenPlayer target) {
        ServerLevel level = helper.getLevel();
        Zombie zombie = EntityType.ZOMBIE.create(level);
        helper.assertTrue(zombie != null, "zombie did not spawn");
        BlockPos at = helper.absolutePos(rel);
        zombie.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        zombie.setTarget(target);
        level.addFreshEntity(zombie);
        return zombie;
    }
}
