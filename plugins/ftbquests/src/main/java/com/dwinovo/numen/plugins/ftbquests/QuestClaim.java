package com.dwinovo.numen.plugins.ftbquests;

import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code numen ftbquests claim}:替她按下任务书上的"领取"按钮。
 *
 * <p>整合包可以把一个奖励设成"要在任务书里手动领取"({@code RewardAutoClaim.DISABLED}),这种奖励 FTB 不会在任务
 * 完成时发,只能由玩家在任务书里点。她点不了,所以走按钮背后同一条服务端处理:队伍进度没被锁、奖励没被封锁、
 * 任务完成了、这件还没领过,就 {@link TeamData#claimReward}——它先记下领奖时刻,再让奖励自己把东西发出去,和
 * {@code ClaimRewardMessage.handle} 认的是同一套。要发什么、随机加量、战利品表、进背包还是掉在脚边,一概不在这里算。
 *
 * <p>任务书里要"选择屏/开箱屏"才能领的奖励(FTB 自己也把 {@code ChoiceReward}、{@code LootReward} 排除在"全部领取"
 * 之外)不在这里领:服务端那把按钮走不到那个屏,领了也只会在记录里划掉却不发东西,所以照实说它们只能在书里点。
 *
 * <p>不带 {@code --quest} 领此刻全部可领的;带上只领那一个任务。领了什么如实回报:每件奖励的名字,以及领完她背包与
 * 经验实际变了什么。任务刚完成时,属于她个人的手动奖励另由 {@link QuestWatch} 当场替她领掉并作为奖励事件报告,
 * 见 {@link #autoClaimPersonal}。
 */
final class QuestClaim {

    private static final Logger LOG = LoggerFactory.getLogger("numen-ftbquests");

    private QuestClaim() {}

    static void claim(ServerSource src, CommandArgs args) {
        NumenPlayer her = src.companion();
        String asked = args.get(FtbqCommands.CLAIM_QUEST);
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        Optional<TeamData> maybe = file.getTeamData(her);
        if (maybe.isEmpty()) {
            src.reply(TaskResult.fail("FTB Quests has no team progress for you, so there is nothing to claim.")
                    .toJson());
            return;
        }
        TeamData team = maybe.get();
        if (team.isLocked()) {
            src.reply(TaskResult.fail("The quest progress of your team \"" + team.getName()
                    + "\" is locked, so nothing can be claimed.").toJson());
            return;
        }

        List<Reward> targets = new ArrayList<>();
        List<String> bookOnly = new ArrayList<>();
        String scope;
        if (asked == null) {
            scope = "your team \"" + team.getName() + "\"";
            file.forAllQuests(quest -> {
                if (team.isCompleted(quest)) {
                    collect(team, her, quest, targets, bookOnly);
                }
            });
        } else {
            List<Quest> found = find(file, asked);
            if (found.isEmpty()) {
                src.reply(TaskResult.fail("No quest has the id or title \"" + asked + "\". Use the id that "
                        + FtbqCommands.LIST + " or " + FtbqCommands.SHOW + " prints.").toJson());
                return;
            }
            if (found.size() > 1) {
                List<String> which = new ArrayList<>();
                for (Quest quest : found) {
                    which.add(quest.getCodeString());
                }
                src.reply(TaskResult.fail("Several quests are titled \"" + asked + "\": " + String.join(", ", which)
                        + ". Name one by its id.").toJson());
                return;
            }
            Quest quest = found.get(0);
            String named = "\"" + quest.getTitle().getString() + "\" (" + quest.getCodeString() + ")";
            if (!team.isCompleted(quest)) {
                src.reply(TaskResult.fail("You cannot claim the rewards of " + named + ": the quest is not "
                        + "completed for your team \"" + team.getName() + "\" yet.").toJson());
                return;
            }
            scope = named + " for your team \"" + team.getName() + "\"";
            collect(team, her, quest, targets, bookOnly);
        }

        String onlyInBook = bookOnly.isEmpty() ? ""
                : "\nLeft for the book (they need its choice or loot screen): " + String.join("; ", bookOnly) + ".";
        if (targets.isEmpty()) {
            src.reply(TaskResult.fail("Nothing is ready to claim for " + scope
                    + ": every reward is already claimed, blocked, or handed out automatically." + onlyInBook)
                    .toJson());
            return;
        }

        Belongings before = new Belongings(her.getInventory().getContainerSize());
        before.copyFrom(her);
        List<String> got = new ArrayList<>();
        List<String> missed = new ArrayList<>();
        for (Reward reward : targets) {
            String label = label(reward);
            team.claimReward(her, reward, false);
            (team.isRewardClaimed(her.getUUID(), reward) ? got : missed).add(label);
        }
        String change = before.changeTo(her);
        StringBuilder body = new StringBuilder();
        for (String line : got) {
            body.append("\n  ").append(line);
        }
        if (!missed.isEmpty()) {
            body.append("\nNot claimed: ").append(String.join("; ", missed)).append('.');
        }
        body.append(change.isEmpty()
                ? "\nYour inventory and experience did not change."
                : "\nYour inventory changed: " + change + ".");
        if (got.isEmpty()) {
            src.reply(TaskResult.fail("Nothing was claimed for " + scope + ":" + body + onlyInBook).toJson());
            return;
        }
        src.reply(TaskResult.ok("Claimed " + got.size() + " reward" + (got.size() == 1 ? "" : "s")
                + " for " + scope + ":" + body + onlyInBook).toJson());
    }

    /**
     * 任务刚完成时替她领掉里面属于她个人、FTB 不会自动发的手动奖励。只碰个人的:团队奖励全队一份,谁领归谁,
     * 不自动替她抢。领取时刻用这一毫秒,和 {@link QuestWatch} 的账对齐(它把这一毫秒的领奖记成"这一轮已经说过")。
     */
    static void autoClaimPersonal(NumenPlayer her, TeamData team, Quest quest, long at) {
        for (Reward reward : quest.getRewards()) {
            if (reward.isTeamReward() || reward.getExcludeFromClaimAll() || team.isRewardBlocked(reward)) {
                continue;
            }
            if (!team.getClaimType(her.getUUID(), reward).canClaim()) {
                continue;
            }
            team.claimReward(her, reward, false, at);
            if (team.isRewardClaimed(her.getUUID(), reward)) {
                LOG.info("FTB Quests: claimed the manual reward \"{}\" of quest \"{}\" for {}",
                        reward.getTitle().getString(), quest.getTitle().getString(),
                        her.getGameProfile().getName());
            }
        }
    }

    /** 她此刻能领的奖励:{@code CAN_CLAIM}(完成了、还没领),没被封锁;要在书里点屏领的另记。 */
    private static void collect(TeamData team, NumenPlayer her, Quest quest, List<Reward> out,
                                List<String> bookOnly) {
        for (Reward reward : quest.getRewards()) {
            if (team.isRewardBlocked(reward) || !team.getClaimType(her.getUUID(), reward).canClaim()) {
                continue;
            }
            if (reward.getExcludeFromClaimAll()) {
                bookOnly.add(label(reward));
            } else {
                out.add(reward);
            }
        }
    }

    /** 编号认 FTB 的十六进制编号;不是编号的按标题整句比(不分大小写)。服务端只有回退语言的标题。 */
    private static List<Quest> find(ServerQuestFile file, String asked) {
        Quest byId = file.getQuest(QuestObjectBase.parseCodeString(asked));
        if (byId != null) {
            return List.of(byId);
        }
        String wanted = asked.strip().toLowerCase(Locale.ROOT);
        List<Quest> byTitle = new ArrayList<>();
        file.forAllQuests(quest -> {
            if (quest.getTitle().getString().toLowerCase(Locale.ROOT).equals(wanted)) {
                byTitle.add(quest);
            }
        });
        return byTitle;
    }

    private static String label(Reward reward) {
        return reward.getTitle().getString() + " (quest \"" + reward.getQuest().getTitle().getString() + "\", "
                + (reward.isTeamReward() ? "team" : "personal") + ")";
    }
}
