import { type BuildAssignment, type BuildPlan, type BuildPlacement, type BuildSection, type ClaimedPlacement } from './buildTypes.js';

function placementKey(placement: BuildPlacement) {
    return `${placement.x},${placement.y},${placement.z}`;
}

function splitIntoSections(plan: BuildPlan): BuildSection[] {
    if (plan.placements.length === 0) {
        return [];
    }

    let minX = Infinity;
    let maxX = -Infinity;
    let minZ = Infinity;
    let maxZ = -Infinity;

    for (const placement of plan.placements) {
        minX = Math.min(minX, placement.x);
        maxX = Math.max(maxX, placement.x);
        minZ = Math.min(minZ, placement.z);
        maxZ = Math.max(maxZ, placement.z);
    }

    const midX = (minX + maxX) / 2;
    const midZ = (minZ + maxZ) / 2;

    const buckets: BuildPlacement[][] = [[], [], [], []];
    for (const placement of plan.placements) {
        const east = placement.x > midX ? 1 : 0;
        const south = placement.z > midZ ? 2 : 0;
        buckets[east + south].push(placement);
    }

    return buckets
        .map((placements, sectionId) => ({
            sectionId,
            placements: placements.sort((a, b) => a.y - b.y)
        }))
        .filter((section) => section.placements.length > 0);
}

export function createBuildCoordinator(plan: BuildPlan) {
    const sections = splitIntoSections(plan);
    const assignments = new Map<string, number>();
    const claimed = new Map<string, ClaimedPlacement>();
    const completed = new Set<string>();

    function assignBot(botName: string): BuildAssignment | null {
        if (assignments.has(botName)) {
            return {
                botName,
                sectionId: assignments.get(botName)
            };
        }

        const usedSections = new Set(assignments.values());
        const next = sections.find((section) => !usedSections.has(section.sectionId))
            || sections
                .filter((section) => remainingInSection(section.sectionId) > 0)
                .sort((a, b) => remainingInSection(b.sectionId) - remainingInSection(a.sectionId))[0];

        if (!next) {
            return null;
        }

        assignments.set(botName, next.sectionId);
        return { botName, sectionId: next.sectionId };
    }

    function remainingInSection(sectionId: number) {
        const section = sections.find((entry) => entry.sectionId === sectionId);
        if (!section) {
            return 0;
        }
        return section.placements.filter((placement) => {
            const key = placementKey(placement);
            return !claimed.has(key) && !completed.has(key);
        }).length;
    }

    function claimNextPlacement(botName: string): ClaimedPlacement | null {
        const assignment = assignBot(botName);
        if (!assignment) {
            return null;
        }

        const section = sections.find((entry) => entry.sectionId === assignment.sectionId);
        if (!section) {
            return null;
        }

        for (const placement of section.placements) {
            const key = placementKey(placement);
            if (claimed.has(key) || completed.has(key)) {
                continue;
            }

            const claim: ClaimedPlacement = {
                ...placement,
                key,
                claimedBy: botName,
                sectionId: section.sectionId
            };
            claimed.set(key, claim);
            return claim;
        }

        return null;
    }

    function markPlacementComplete(botName: string, key: string) {
        const claim = claimed.get(key);
        if (!claim || claim.claimedBy !== botName) {
            return false;
        }

        claimed.delete(key);
        completed.add(key);
        return true;
    }

    function rebalanceBot(botName: string): BuildAssignment | null {
        const next = sections
            .filter((section) => remainingInSection(section.sectionId) > 0)
            .sort((a, b) => remainingInSection(b.sectionId) - remainingInSection(a.sectionId))[0];

        if (!next) {
            return null;
        }

        assignments.set(botName, next.sectionId);
        return { botName, sectionId: next.sectionId };
    }

    function getProgress() {
        return {
            total: plan.placements.length,
            completed: completed.size,
            claimed: claimed.size
        };
    }

    return {
        sections,
        assignBot,
        claimNextPlacement,
        markPlacementComplete,
        rebalanceBot,
        getProgress
    };
}
