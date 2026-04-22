// @ts-nocheck

export function createBlockLookup(blockNames) {
    const allowed = new Set(blockNames || []);
    return {
        has(blockName) {
            return allowed.has(blockName);
        },
        values() {
            return [...allowed];
        }
    };
}

export function resourceLocationKey(location) {
    return `${location.blockName}:${location.x}:${location.y}:${location.z}`;
}

function scoreTarget(origin, candidate) {
    const verticalPenalty = Math.abs((candidate.y || 0) - (origin.y || 0)) * 2;
    const exposureBonus = -(candidate.exposedFaces || 0) * 6;
    return (candidate.distanceSq || 0) + verticalPenalty + exposureBonus;
}

export function selectBestMiningTarget(origin, knownLocations, lookup, blacklist = new Set()) {
    const candidates = (knownLocations || [])
        .filter(candidate => lookup.has(candidate.blockName))
        .filter(candidate => !blacklist.has(resourceLocationKey(candidate)))
        .sort((a, b) => scoreTarget(origin, a) - scoreTarget(origin, b));

    return candidates[0] || null;
}

export function summarizeKnownLocations(knownLocations) {
    const counts = {};
    for (const location of knownLocations || []) {
        counts[location.blockName] = (counts[location.blockName] || 0) + 1;
    }
    return counts;
}
