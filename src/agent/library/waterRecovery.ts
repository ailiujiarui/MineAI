// @ts-nocheck

function distanceSquared(a, b) {
    const dx = a.x - b.x;
    const dy = a.y - b.y;
    const dz = a.z - b.z;
    return dx * dx + dy * dy + dz * dz;
}

export function shouldTriggerWaterEscape(state) {
    const waterAtFeet = state.blockAtFeet === 'water';
    const waterAtHead = state.blockAtHead === 'water';
    const lowProgress = (state.horizontalSpeed || 0) < 0.03;
    const nearestDryDistance = state.nearestDryDistance ?? Infinity;
    const shallowWater = waterAtFeet && !waterAtHead;
    const quickDryEscape = shallowWater && nearestDryDistance <= 1.5;
    const stuckEnough = (state.stuckSeconds || 0) >= (shallowWater ? 5 : 2);

    if (quickDryEscape) {
        return false;
    }

    return (waterAtFeet || waterAtHead) && lowProgress && stuckEnough;
}

export function chooseWaterEscapeTarget(origin, candidates) {
    if (!Array.isArray(candidates) || candidates.length === 0) {
        return null;
    }

    const scored = candidates
        .filter(candidate => candidate.solidGround && candidate.headClear)
        .map(candidate => {
            const upwardBias = candidate.y > origin.y ? -3 : 0;
            const waterPenalty = (candidate.waterNeighbors || 0) * 5;
            const distancePenalty = distanceSquared(origin, candidate);
            return {
                candidate,
                score: waterPenalty + distancePenalty + upwardBias
            };
        })
        .sort((a, b) => a.score - b.score);

    return scored[0]?.candidate || null;
}
