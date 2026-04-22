export function createCombatNavigator(pf) {
    return {
        async moveWithinRange(bot, entity, desiredDistance = 3.2) {
            const distance = bot.entity.position.distanceTo(entity.position)
            if (distance <= desiredDistance) {
                return false
            }

            bot.pathfinder.setMovements(new pf.Movements(bot))
            bot.pathfinder.setGoal(new pf.goals.GoalFollow(entity, desiredDistance), true)
            return true
        },

        async moveAwayIfTooClose(bot, entity, safeDistance = 2.2) {
            const distance = bot.entity.position.distanceTo(entity.position)
            if (distance >= safeDistance) {
                return false
            }

            bot.pathfinder.setMovements(new pf.Movements(bot))
            const followGoal = new pf.goals.GoalFollow(entity, safeDistance)
            bot.pathfinder.setGoal(new pf.goals.GoalInvert(followGoal), true)
            return true
        },

        stop(bot) {
            bot.pathfinder.stop()
        }
    }
}
