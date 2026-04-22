// @ts-nocheck

export function chooseExploreTarget(origin, options = {}) {
    const radius = options.radius || 32;
    const explored = new Set(options.exploredWaypoints || []);
    const offsets = [
        { x: radius, z: 0 },
        { x: 0, z: radius },
        { x: -radius, z: 0 },
        { x: 0, z: -radius },
        { x: radius, z: radius },
        { x: -radius, z: radius },
        { x: -radius, z: -radius },
        { x: radius, z: -radius }
    ];

    for (const offset of offsets) {
        const candidate = {
            x: Math.floor(origin.x + offset.x),
            y: Math.floor(origin.y),
            z: Math.floor(origin.z + offset.z)
        };
        const key = `${candidate.x},${candidate.z}`;
        if (!explored.has(key)) {
            return candidate;
        }
    }

    return {
        x: Math.floor(origin.x + radius),
        y: Math.floor(origin.y),
        z: Math.floor(origin.z)
    };
}
